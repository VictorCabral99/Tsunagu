package sync

import (
	"slices"
	"testing"

	"tsunagu/backend/internal/db/sqlcgen"
)

func chapters(ids ...string) []sqlcgen.Chapter {
	out := make([]sqlcgen.Chapter, len(ids))
	for i, id := range ids {
		out[i] = sqlcgen.Chapter{ID: int64(i + 1), ExternalID: id}
	}
	return out
}

func TestNewChapterIDs(t *testing.T) {
	cases := []struct {
		name   string
		before []string
		after  []sqlcgen.Chapter
		want   []int64
	}{
		{"first-ever sync is a backlog, not new", nil, chapters("a", "b", "c"), nil},
		{"nothing new", []string{"a", "b"}, chapters("a", "b"), nil},
		{"new releases", []string{"a", "b"}, chapters("a", "b", "c", "d"), []int64{3, 4}},
		{"new chapter inserted mid-list", []string{"a", "c"}, chapters("a", "b", "c"), []int64{2}},
		{"source dropped a chapter", []string{"a", "b", "c"}, chapters("a", "c"), nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := newChapterIDs(c.before, c.after); !slices.Equal(got, c.want) {
				t.Fatalf("got %v, want %v", got, c.want)
			}
		})
	}
}
