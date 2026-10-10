-- name: GetAutomationSettings :one
SELECT * FROM automation_settings WHERE id = 1;

-- name: UpdateAutomationSettings :one
UPDATE automation_settings SET
    enabled = ?,
    enforce_global = ?,
    auto_download = ?,
    download_ahead = ?,
    delete_on_read = ?,
    delete_delay_hours = ?,
    max_keep_chapters = ?,
    refresh_interval = ?,
    update_on_open = ?,
    configured = 1
WHERE id = 1
RETURNING *;

-- name: SetLastLibraryUpdateAt :exec
UPDATE automation_settings SET last_library_update_at = ? WHERE id = 1;

-- name: GetMediaAutomation :one
SELECT * FROM media_automation WHERE media_id = ?;

-- name: ListMediaAutomation :many
SELECT * FROM media_automation ORDER BY media_id;

-- name: UpsertMediaAutomation :one
INSERT INTO media_automation (
    media_id, auto_download, download_ahead, delete_on_read, delete_delay_hours,
    max_keep_chapters, pause_updates, refresh_interval
) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (media_id) DO UPDATE SET
    auto_download = excluded.auto_download,
    download_ahead = excluded.download_ahead,
    delete_on_read = excluded.delete_on_read,
    delete_delay_hours = excluded.delete_delay_hours,
    max_keep_chapters = excluded.max_keep_chapters,
    pause_updates = excluded.pause_updates,
    refresh_interval = excluded.refresh_interval
RETURNING *;

-- name: DeleteMediaAutomation :exec
DELETE FROM media_automation WHERE media_id = ?;

-- name: DeleteAllMediaAutomation :exec
DELETE FROM media_automation;

-- name: ScheduleChapterDeletion :exec
INSERT INTO scheduled_deletions (chapter_id, delete_at) VALUES (?, ?)
ON CONFLICT (chapter_id) DO UPDATE SET delete_at = excluded.delete_at;

-- name: CancelChapterDeletions :exec
DELETE FROM scheduled_deletions WHERE chapter_id IN (sqlc.slice('chapter_ids'));

-- name: ListDueChapterDeletions :many
SELECT sd.chapter_id, c.media_id FROM scheduled_deletions sd
JOIN chapters c ON c.id = sd.chapter_id
WHERE sd.delete_at <= ?
ORDER BY sd.delete_at;

-- name: SetMediaChaptersCheckedAt :exec
UPDATE media SET chapters_checked_at = ? WHERE id = ?;

-- name: ListUpdateTargetsWithCheckedAt :many
SELECT m.id, m.chapters_checked_at FROM media m
WHERE m.id IN (sqlc.slice('media_ids'));

-- name: MediaInAutoDownloadFolder :one
SELECT EXISTS (
    SELECT 1 FROM media_folders mf
    JOIN folders f ON f.id = mf.folder_id
    WHERE mf.media_id = ? AND f.include_in_download = 1
) AS in_folder;

-- name: ListChapterAutomationState :many
-- Chapters in reading order with what automation needs to know about each.
SELECT
    c.id,
    CAST(EXISTS (
        SELECT 1 FROM reading_progress rp WHERE rp.chapter_id = c.id AND rp.completed = TRUE
    ) AS INTEGER) AS is_read,
    CAST(EXISTS (
        SELECT 1 FROM downloads d WHERE d.chapter_id = c.id AND d.status = 'done'
    ) AND (
        EXISTS (SELECT 1 FROM manga_pages mp WHERE mp.chapter_id = c.id AND mp.local_path IS NOT NULL AND mp.local_path != '')
        OR EXISTS (SELECT 1 FROM novel_chapter_content nc WHERE nc.chapter_id = c.id AND nc.local_path IS NOT NULL AND nc.local_path != '')
        OR EXISTS (SELECT 1 FROM anime_episode_streams a WHERE a.chapter_id = c.id AND a.local_path IS NOT NULL AND a.local_path != '')
    ) AS INTEGER) AS is_downloaded,
    CAST(EXISTS (
        SELECT 1 FROM downloads d WHERE d.chapter_id = c.id AND d.status IN ('queued', 'downloading')
    ) AS INTEGER) AS is_pending
FROM chapters c
WHERE c.media_id = ?
ORDER BY c.source_order, c.number;
