-- Server-side automation: global rules (single row), per-series overrides
-- (NULL column = inherit the global value), and delayed delete-after-read jobs.
CREATE TABLE automation_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled INTEGER NOT NULL DEFAULT 1,
    enforce_global INTEGER NOT NULL DEFAULT 0,
    auto_download INTEGER NOT NULL DEFAULT 0,
    download_ahead INTEGER NOT NULL DEFAULT 0,
    delete_on_read INTEGER NOT NULL DEFAULT 0,
    delete_delay_hours INTEGER NOT NULL DEFAULT 0,
    max_keep_chapters INTEGER NOT NULL DEFAULT 0,
    refresh_interval TEXT NOT NULL DEFAULT 'daily' CHECK (refresh_interval IN ('daily', 'weekly', 'manual')),
    update_on_open INTEGER NOT NULL DEFAULT 1,
    -- Set once any client saves settings; lets clients migrate older local rules exactly once.
    configured INTEGER NOT NULL DEFAULT 0,
    last_library_update_at TIMESTAMP
);
INSERT INTO automation_settings (id) VALUES (1);

CREATE TABLE media_automation (
    media_id INTEGER PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
    auto_download INTEGER,
    download_ahead INTEGER,
    delete_on_read INTEGER,
    delete_delay_hours INTEGER,
    max_keep_chapters INTEGER,
    pause_updates INTEGER,
    refresh_interval TEXT CHECK (refresh_interval IS NULL OR refresh_interval IN ('daily', 'weekly', 'manual'))
);

CREATE TABLE scheduled_deletions (
    chapter_id INTEGER PRIMARY KEY REFERENCES chapters(id) ON DELETE CASCADE,
    delete_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_scheduled_deletions_delete_at ON scheduled_deletions(delete_at);

-- When a library update last checked this series for new chapters.
ALTER TABLE media ADD COLUMN chapters_checked_at TIMESTAMP;
