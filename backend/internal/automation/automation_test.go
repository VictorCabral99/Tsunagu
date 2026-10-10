package automation

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"tsunagu/backend/internal/db"
	"tsunagu/backend/internal/db/sqlcgen"
	"tsunagu/backend/internal/download"
	syncpkg "tsunagu/backend/internal/sync"
)

type fixture struct {
	t    *testing.T
	conn *sql.DB
	q    *sqlcgen.Queries
	m    *Manager
	dir  string
}

// newFixture creates one manga with five chapters in reading order (ids 1-5);
// downloaded lists the chapter ids that have a finished download on disk.
func newFixture(t *testing.T, downloaded ...int64) *fixture {
	t.Helper()
	dir := t.TempDir()
	conn, err := db.Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	if _, err := conn.Exec(`
		INSERT INTO media (id, external_id, content_type, title, added_at) VALUES (1, 'm', 'manga', 'Title', CURRENT_TIMESTAMP);
		INSERT INTO chapters (id, media_id, external_id, source_order) VALUES
			(1, 1, 'c1', 1), (2, 1, 'c2', 2), (3, 1, 'c3', 3), (4, 1, 'c4', 4), (5, 1, 'c5', 5);
	`); err != nil {
		t.Fatal(err)
	}
	q := sqlcgen.New(conn)
	f := &fixture{t: t, conn: conn, q: q, dir: dir}
	for _, id := range downloaded {
		f.addDownload(id)
	}
	dm := download.New(q, nil, dir, dir, "loose")
	sy := syncpkg.New(conn, q, dir, dir)
	f.m = New(q, dm, sy, nil)
	return f
}

func (f *fixture) pagePath(chapterID int64) string {
	return filepath.Join(f.dir, "manga", "Ext", "Title", "Ch"+string(rune('0'+chapterID)), "1.jpg")
}

func (f *fixture) addDownload(chapterID int64) {
	p := f.pagePath(chapterID)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		f.t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		f.t.Fatal(err)
	}
	f.exec(`INSERT INTO manga_pages (chapter_id, page_number, local_path) VALUES (?, 1, ?)`, chapterID, p)
	f.exec(`INSERT INTO downloads (chapter_id, status, progress) VALUES (?, 'done', 1)`, chapterID)
}

func (f *fixture) exec(query string, args ...any) {
	f.t.Helper()
	if _, err := f.conn.Exec(query, args...); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) markRead(ids ...int64) {
	for _, id := range ids {
		f.exec(`INSERT INTO reading_progress (media_id, chapter_id, progress, completed) VALUES (1, ?, 1, TRUE)`, id)
	}
}

func (f *fixture) state() (downloaded, pending []int64) {
	f.t.Helper()
	rows, err := f.q.ListChapterAutomationState(context.Background(), 1)
	if err != nil {
		f.t.Fatal(err)
	}
	for _, r := range rows {
		if r.IsDownloaded != 0 {
			downloaded = append(downloaded, r.ID)
		}
		if r.IsPending != 0 {
			pending = append(pending, r.ID)
		}
	}
	return downloaded, pending
}

func (f *fixture) fileExists(chapterID int64) bool {
	_, err := os.Stat(f.pagePath(chapterID))
	return err == nil
}

func TestRulesForPrecedence(t *testing.T) {
	global := sqlcgen.AutomationSetting{Enabled: 1, AutoDownload: 0, DownloadAhead: 2, DeleteOnRead: 1, RefreshInterval: IntervalDaily}
	ov := &sqlcgen.MediaAutomation{
		AutoDownload:    sql.NullInt64{Int64: 1, Valid: true},
		PauseUpdates:    sql.NullInt64{Int64: 1, Valid: true},
		RefreshInterval: sql.NullString{String: IntervalWeekly, Valid: true},
	}

	r := RulesFor(global, nil)
	if r.AutoDownload || r.DownloadAhead != 2 || !r.DeleteOnRead || r.PauseUpdates || r.RefreshInterval != IntervalDaily {
		t.Fatalf("global only: %+v", r)
	}
	r = RulesFor(global, ov)
	if !r.AutoDownload || r.DownloadAhead != 2 || !r.PauseUpdates || r.RefreshInterval != IntervalWeekly {
		t.Fatalf("override should win where set, inherit elsewhere: %+v", r)
	}

	enforced := global
	enforced.EnforceGlobal = 1
	if r := RulesFor(enforced, ov); r.AutoDownload || r.PauseUpdates || r.RefreshInterval != IntervalDaily {
		t.Fatalf("enforce global should ignore overrides: %+v", r)
	}

	disabled := global
	disabled.Enabled = 0
	if r := RulesFor(disabled, ov); r.AutoDownload || r.DownloadAhead != 0 || r.DeleteOnRead || !r.PauseUpdates {
		t.Fatalf("disabled should stop downloads/deletions but keep pause: %+v", r)
	}
}

func TestDeleteOnReadAndDownloadAhead(t *testing.T) {
	f := newFixture(t, 1, 2, 3)
	f.exec(`UPDATE automation_settings SET delete_on_read = 1, download_ahead = 2`)
	f.markRead(2)

	f.m.ChaptersRead(context.Background(), 1, []int64{2})

	downloaded, pending := f.state()
	if !slices.Equal(downloaded, []int64{1, 3}) {
		t.Fatalf("downloaded = %v, want [1 3] (chapter 2 deleted after read)", downloaded)
	}
	if f.fileExists(2) {
		t.Fatal("chapter 2 file still on disk")
	}
	// Next two after chapter 2 are 3 (already downloaded) and 4.
	if !slices.Equal(pending, []int64{4}) {
		t.Fatalf("queued = %v, want [4]", pending)
	}
}

func TestDelayedDeleteOnRead(t *testing.T) {
	f := newFixture(t, 1)
	f.exec(`UPDATE automation_settings SET delete_on_read = 1, delete_delay_hours = 24`)
	f.markRead(1)
	ctx := context.Background()

	f.m.ChaptersRead(ctx, 1, []int64{1})
	if !f.fileExists(1) {
		t.Fatal("delayed delete removed the file immediately")
	}
	f.m.runDueDeletions(ctx)
	if !f.fileExists(1) {
		t.Fatal("deleted before the delay elapsed")
	}

	f.exec(`UPDATE scheduled_deletions SET delete_at = ?`, time.Now().UTC().Add(-time.Minute))
	f.m.runDueDeletions(ctx)
	if f.fileExists(1) {
		t.Fatal("due deletion did not run")
	}
	var n int
	if err := f.conn.QueryRow(`SELECT COUNT(*) FROM scheduled_deletions`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("scheduled deletions left: %d err=%v", n, err)
	}
}

func TestUnreadCancelsDelayedDelete(t *testing.T) {
	f := newFixture(t, 1)
	f.exec(`UPDATE automation_settings SET delete_on_read = 1, delete_delay_hours = 1`)
	ctx := context.Background()
	f.m.ChaptersRead(ctx, 1, []int64{1})
	f.m.ChaptersUnread(ctx, []int64{1})
	f.exec(`UPDATE scheduled_deletions SET delete_at = ?`, time.Now().UTC().Add(-time.Minute))
	f.m.runDueDeletions(ctx)
	if !f.fileExists(1) {
		t.Fatal("marking unread should cancel the pending deletion")
	}
}

func TestMaxKeepDeletesOldest(t *testing.T) {
	f := newFixture(t, 1, 2, 3)
	f.exec(`UPDATE automation_settings SET max_keep_chapters = 1`)
	f.markRead(1)

	f.m.ChaptersRead(context.Background(), 1, []int64{1})

	if downloaded, _ := f.state(); !slices.Equal(downloaded, []int64{3}) {
		t.Fatalf("downloaded = %v, want [3]", downloaded)
	}
}

func TestDisabledAutomationDoesNothing(t *testing.T) {
	f := newFixture(t, 1)
	f.exec(`UPDATE automation_settings SET enabled = 0, delete_on_read = 1, download_ahead = 3`)
	f.markRead(1)
	f.m.ChaptersRead(context.Background(), 1, []int64{1})
	downloaded, pending := f.state()
	if !slices.Equal(downloaded, []int64{1}) || len(pending) != 0 {
		t.Fatalf("downloaded=%v pending=%v, want untouched", downloaded, pending)
	}
}

func TestAutoDownloadNewChapters(t *testing.T) {
	ctx := context.Background()

	f := newFixture(t)
	f.m.onSeriesChecked(ctx, 1, []int64{4, 5})
	if _, pending := f.state(); len(pending) != 0 {
		t.Fatalf("queued %v with auto-download off", pending)
	}
	var checked sql.NullTime
	if err := f.conn.QueryRow(`SELECT chapters_checked_at FROM media WHERE id = 1`).Scan(&checked); err != nil || !checked.Valid {
		t.Fatalf("chapters_checked_at not recorded: %v %v", checked, err)
	}

	f.exec(`UPDATE automation_settings SET auto_download = 1`)
	f.m.onSeriesChecked(ctx, 1, []int64{5, 4})
	if _, pending := f.state(); !slices.Equal(pending, []int64{4, 5}) {
		t.Fatalf("queued %v, want [4 5]", pending)
	}

	// A folder flagged for auto-download works without the global switch.
	g := newFixture(t)
	g.exec(`INSERT INTO folders (id, name, kind, include_in_download) VALUES (90, 'Reading Now', 'custom', 1)`)
	g.exec(`INSERT INTO media_folders (media_id, folder_id) VALUES (1, 90)`)
	g.m.onSeriesChecked(ctx, 1, []int64{5})
	if _, pending := g.state(); !slices.Equal(pending, []int64{5}) {
		t.Fatalf("folder auto-download queued %v, want [5]", pending)
	}
}

func TestFilterTargets(t *testing.T) {
	f := newFixture(t)
	f.exec(`INSERT INTO media (id, external_id, content_type, title, added_at) VALUES
		(2, 'paused', 'manga', 'Paused', CURRENT_TIMESTAMP),
		(3, 'manual', 'manga', 'Manual', CURRENT_TIMESTAMP),
		(4, 'weekly', 'manga', 'Weekly', CURRENT_TIMESTAMP)`)
	f.exec(`INSERT INTO media_automation (media_id, pause_updates) VALUES (2, 1)`)
	f.exec(`INSERT INTO media_automation (media_id, refresh_interval) VALUES (3, 'manual'), (4, 'weekly')`)
	now := time.Now().UTC()
	f.exec(`UPDATE media SET chapters_checked_at = ? WHERE id IN (1, 4)`, now.Add(-30*time.Hour))
	ctx := context.Background()
	s, err := f.q.GetAutomationSettings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	all := []int64{1, 2, 3, 4}

	got, _ := f.m.filterTargets(ctx, s, all, TriggerManual, now)
	if !slices.Equal(got, []int64{1, 3, 4}) {
		t.Fatalf("manual = %v, want [1 3 4] (paused skipped)", got)
	}
	got, _ = f.m.filterTargets(ctx, s, all, TriggerAppOpen, now)
	if !slices.Equal(got, []int64{1, 4}) {
		t.Fatalf("app open = %v, want [1 4] (paused and manual skipped)", got)
	}
	// Daily series checked 30h ago is due; weekly series checked 30h ago isn't.
	got, _ = f.m.filterTargets(ctx, s, all, TriggerScheduled, now)
	if !slices.Equal(got, []int64{1}) {
		t.Fatalf("scheduled = %v, want [1]", got)
	}
}
