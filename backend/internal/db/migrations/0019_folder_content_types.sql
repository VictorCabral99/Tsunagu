-- Comma-separated content types a folder is designated for ("manga,novel").
-- Empty means the folder applies to every content type.
ALTER TABLE folders ADD COLUMN content_types TEXT NOT NULL DEFAULT '';
