package graph

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"

	"tsunagu/backend/internal/api/graph/model"
	"tsunagu/backend/internal/automation"
	"tsunagu/backend/internal/db/sqlcgen"
)

func refreshIntervalFromDB(s string) model.RefreshInterval {
	switch s {
	case automation.IntervalWeekly:
		return model.RefreshIntervalWeekly
	case automation.IntervalManual:
		return model.RefreshIntervalManual
	}
	return model.RefreshIntervalDaily
}

func refreshIntervalToDB(r model.RefreshInterval) string {
	switch r {
	case model.RefreshIntervalWeekly:
		return automation.IntervalWeekly
	case model.RefreshIntervalManual:
		return automation.IntervalManual
	}
	return automation.IntervalDaily
}

func toAutomationSettings(s sqlcgen.AutomationSetting) *model.AutomationSettings {
	out := &model.AutomationSettings{
		Enabled:          s.Enabled != 0,
		EnforceGlobal:    s.EnforceGlobal != 0,
		AutoDownload:     s.AutoDownload != 0,
		DownloadAhead:    int32(s.DownloadAhead),
		DeleteOnRead:     s.DeleteOnRead != 0,
		DeleteDelayHours: int32(s.DeleteDelayHours),
		MaxKeepChapters:  int32(s.MaxKeepChapters),
		RefreshInterval:  refreshIntervalFromDB(s.RefreshInterval),
		UpdateOnOpen:     s.UpdateOnOpen != 0,
		Configured:       s.Configured != 0,
	}
	if s.LastLibraryUpdateAt.Valid {
		t := s.LastLibraryUpdateAt.Time
		out.LastLibraryUpdateAt = &t
	}
	return out
}

func toAutomationRules(r automation.Rules) *model.AutomationRules {
	return &model.AutomationRules{
		AutoDownload:     r.AutoDownload,
		DownloadAhead:    int32(r.DownloadAhead),
		DeleteOnRead:     r.DeleteOnRead,
		DeleteDelayHours: int32(r.DeleteDelayHours),
		MaxKeepChapters:  int32(r.MaxKeepChapters),
		PauseUpdates:     r.PauseUpdates,
		RefreshInterval:  refreshIntervalFromDB(r.RefreshInterval),
	}
}

func nullIntBoolPtr(v sql.NullInt64) *bool {
	if !v.Valid {
		return nil
	}
	b := v.Int64 != 0
	return &b
}

func nullInt32Ptr(v sql.NullInt64) *int32 {
	if !v.Valid {
		return nil
	}
	n := int32(v.Int64)
	return &n
}

func toMediaAutomation(s sqlcgen.AutomationSetting, mediaID int64, ov *sqlcgen.MediaAutomation) *model.MediaAutomation {
	out := &model.MediaAutomation{
		MediaID:   strconv.FormatInt(mediaID, 10),
		Effective: toAutomationRules(automation.RulesFor(s, ov)),
	}
	if ov == nil {
		return out
	}
	out.AutoDownload = nullIntBoolPtr(ov.AutoDownload)
	out.DownloadAhead = nullInt32Ptr(ov.DownloadAhead)
	out.DeleteOnRead = nullIntBoolPtr(ov.DeleteOnRead)
	out.DeleteDelayHours = nullInt32Ptr(ov.DeleteDelayHours)
	out.MaxKeepChapters = nullInt32Ptr(ov.MaxKeepChapters)
	out.PauseUpdates = nullIntBoolPtr(ov.PauseUpdates)
	if ov.RefreshInterval.Valid {
		ri := refreshIntervalFromDB(ov.RefreshInterval.String)
		out.RefreshInterval = &ri
	}
	return out
}

func boolInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

func nonNegative(name string, v *int32) error {
	if v != nil && *v < 0 {
		return fmt.Errorf("%s must not be negative", name)
	}
	return nil
}

func validateAutomationNumbers(downloadAhead, deleteDelayHours, maxKeep *int32) error {
	return errors.Join(
		nonNegative("downloadAhead", downloadAhead),
		nonNegative("deleteDelayHours", deleteDelayHours),
		nonNegative("maxKeepChapters", maxKeep),
	)
}

func (r *Resolver) updateAutomationSettings(ctx context.Context, in model.AutomationSettingsInput) (*model.AutomationSettings, error) {
	if err := validateAutomationNumbers(in.DownloadAhead, in.DeleteDelayHours, in.MaxKeepChapters); err != nil {
		return nil, err
	}
	cur, err := r.Q.GetAutomationSettings(ctx)
	if err != nil {
		return nil, err
	}
	p := sqlcgen.UpdateAutomationSettingsParams{
		Enabled:          cur.Enabled,
		EnforceGlobal:    cur.EnforceGlobal,
		AutoDownload:     cur.AutoDownload,
		DownloadAhead:    cur.DownloadAhead,
		DeleteOnRead:     cur.DeleteOnRead,
		DeleteDelayHours: cur.DeleteDelayHours,
		MaxKeepChapters:  cur.MaxKeepChapters,
		RefreshInterval:  cur.RefreshInterval,
		UpdateOnOpen:     cur.UpdateOnOpen,
	}
	if in.Enabled != nil {
		p.Enabled = boolInt(*in.Enabled)
	}
	if in.EnforceGlobal != nil {
		p.EnforceGlobal = boolInt(*in.EnforceGlobal)
	}
	if in.AutoDownload != nil {
		p.AutoDownload = boolInt(*in.AutoDownload)
	}
	if in.DownloadAhead != nil {
		p.DownloadAhead = int64(*in.DownloadAhead)
	}
	if in.DeleteOnRead != nil {
		p.DeleteOnRead = boolInt(*in.DeleteOnRead)
	}
	if in.DeleteDelayHours != nil {
		p.DeleteDelayHours = int64(*in.DeleteDelayHours)
	}
	if in.MaxKeepChapters != nil {
		p.MaxKeepChapters = int64(*in.MaxKeepChapters)
	}
	if in.RefreshInterval != nil {
		p.RefreshInterval = refreshIntervalToDB(*in.RefreshInterval)
	}
	if in.UpdateOnOpen != nil {
		p.UpdateOnOpen = boolInt(*in.UpdateOnOpen)
	}
	s, err := r.Q.UpdateAutomationSettings(ctx, p)
	if err != nil {
		return nil, err
	}
	return toAutomationSettings(s), nil
}

func optBool(b *bool) sql.NullInt64 {
	if b == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: boolInt(*b), Valid: true}
}

func optInt(n *int32) sql.NullInt64 {
	if n == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: int64(*n), Valid: true}
}

func (r *Resolver) mediaAutomation(ctx context.Context, mediaID int64) (*model.MediaAutomation, error) {
	s, err := r.Q.GetAutomationSettings(ctx)
	if err != nil {
		return nil, err
	}
	ov, err := r.Q.GetMediaAutomation(ctx, mediaID)
	if errors.Is(err, sql.ErrNoRows) {
		return toMediaAutomation(s, mediaID, nil), nil
	}
	if err != nil {
		return nil, err
	}
	return toMediaAutomation(s, mediaID, &ov), nil
}

func (r *Resolver) setMediaAutomation(ctx context.Context, mediaID int64, in model.MediaAutomationInput) (*model.MediaAutomation, error) {
	if err := validateAutomationNumbers(in.DownloadAhead, in.DeleteDelayHours, in.MaxKeepChapters); err != nil {
		return nil, err
	}
	if _, err := r.Q.GetMedia(ctx, mediaID); err != nil {
		return nil, fmt.Errorf("media %d: %w", mediaID, err)
	}
	p := sqlcgen.UpsertMediaAutomationParams{
		MediaID:          mediaID,
		AutoDownload:     optBool(in.AutoDownload),
		DownloadAhead:    optInt(in.DownloadAhead),
		DeleteOnRead:     optBool(in.DeleteOnRead),
		DeleteDelayHours: optInt(in.DeleteDelayHours),
		MaxKeepChapters:  optInt(in.MaxKeepChapters),
		PauseUpdates:     optBool(in.PauseUpdates),
	}
	if in.RefreshInterval != nil {
		p.RefreshInterval = sql.NullString{String: refreshIntervalToDB(*in.RefreshInterval), Valid: true}
	}
	if !p.AutoDownload.Valid && !p.DownloadAhead.Valid && !p.DeleteOnRead.Valid && !p.DeleteDelayHours.Valid &&
		!p.MaxKeepChapters.Valid && !p.PauseUpdates.Valid && !p.RefreshInterval.Valid {
		// Nothing overridden: drop the row so the series simply inherits.
		if err := r.Q.DeleteMediaAutomation(ctx, mediaID); err != nil {
			return nil, err
		}
		return r.mediaAutomation(ctx, mediaID)
	}
	if _, err := r.Q.UpsertMediaAutomation(ctx, p); err != nil {
		return nil, err
	}
	return r.mediaAutomation(ctx, mediaID)
}

func (r *Resolver) mediaAutomationOverrides(ctx context.Context) ([]*model.MediaAutomation, error) {
	s, err := r.Q.GetAutomationSettings(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := r.Q.ListMediaAutomation(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]*model.MediaAutomation, 0, len(rows))
	for i := range rows {
		out = append(out, toMediaAutomation(s, rows[i].MediaID, &rows[i]))
	}
	return out, nil
}

// afterChaptersMarked hands read/unread changes to the automation manager
// without holding up the request.
func (r *Resolver) afterChaptersMarked(mediaID int64, chapterIDs []int64, read bool) {
	if r.Au == nil || len(chapterIDs) == 0 {
		return
	}
	go func() {
		ctx := context.Background()
		if read {
			r.Au.ChaptersRead(ctx, mediaID, chapterIDs)
		} else {
			r.Au.ChaptersUnread(ctx, chapterIDs)
		}
	}()
}
