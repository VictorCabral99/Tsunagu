package sync

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

	tdb "tsunagu/backend/internal/db"
	"tsunagu/backend/internal/db/sqlcgen"
	"tsunagu/backend/internal/repository"
)

func TestApplyRepoIndexKeepsPackageOwnedByAnotherRepo(t *testing.T) {
	ctx := context.Background()
	conn, err := tdb.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	q := sqlcgen.New(conn)
	s := New(conn, q, t.TempDir(), t.TempDir())

	newRepo := func(url string) sqlcgen.Repository {
		repo, err := q.CreateRepository(ctx, sqlcgen.CreateRepositoryParams{
			IndexUrl:    url,
			Name:        sql.NullString{String: url, Valid: true},
			ContentType: "anime",
		})
		if err != nil {
			t.Fatalf("create repository: %v", err)
		}
		return repo
	}
	first := newRepo("https://example.com/first/index.min.json")
	second := newRepo("https://example.com/second/index.min.json")

	ext := func(pkg, version, apk string) repository.ParsedExtension {
		return repository.ParsedExtension{
			Name: pkg, PackageName: pkg, VersionName: version, ApkURL: apk, Lang: "pt-BR", ContentType: "anime",
		}
	}

	if err := s.applyRepoIndex(ctx, first.ID, []repository.ParsedExtension{
		ext("eu.kanade.shared", "1.0", "https://example.com/first/shared.apk"),
	}); err != nil {
		t.Fatalf("apply first: %v", err)
	}
	if err := s.applyRepoIndex(ctx, second.ID, []repository.ParsedExtension{
		ext("eu.kanade.shared", "9.9", "https://example.com/second/shared.apk"),
		ext("eu.kanade.only.second", "2.0", "https://example.com/second/only.apk"),
	}); err != nil {
		t.Fatalf("apply second: %v", err)
	}

	shared, err := q.GetExtensionByPackageName(ctx, "eu.kanade.shared")
	if err != nil {
		t.Fatalf("get shared: %v", err)
	}
	if shared.RepositoryID != first.ID || shared.Version != "1.0" || shared.ApkUrl != "https://example.com/first/shared.apk" {
		t.Fatalf("shared package was overwritten by the second repo: %+v", shared)
	}

	only, err := q.GetExtensionByPackageName(ctx, "eu.kanade.only.second")
	if err != nil {
		t.Fatalf("get second-only: %v", err)
	}
	if only.RepositoryID != second.ID {
		t.Fatalf("second-only package belongs to repo %d, want %d", only.RepositoryID, second.ID)
	}

	if err := s.applyRepoIndex(ctx, first.ID, []repository.ParsedExtension{
		ext("eu.kanade.shared", "1.1", "https://example.com/first/shared.apk"),
	}); err != nil {
		t.Fatalf("re-sync first: %v", err)
	}
	shared, _ = q.GetExtensionByPackageName(ctx, "eu.kanade.shared")
	if shared.Version != "1.1" {
		t.Fatalf("owning repo could not update its package: version %q", shared.Version)
	}
}
