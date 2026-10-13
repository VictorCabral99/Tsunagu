package tracker

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// redirect sends every request to the test server, keeping path and query.
type redirect struct{ target *url.URL }

func (r redirect) RoundTrip(req *http.Request) (*http.Response, error) {
	req.URL.Scheme, req.URL.Host = r.target.Scheme, r.target.Host
	return http.DefaultTransport.RoundTrip(req)
}

func TestMALListLibraryPages(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "no auth", http.StatusUnauthorized)
			return
		}
		if !strings.HasSuffix(r.URL.Path, "/users/@me/animelist") {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("offset") == "" {
			fmt.Fprintf(w, `{"data":[
				{"node":{"id":52991,"title":"Sousou no Frieren","num_episodes":28,"main_picture":{"large":"https://cdn/f.jpg"},"alternative_titles":{"en":"Frieren: Beyond Journey's End"}},
				 "list_status":{"status":"completed","score":10,"num_episodes_watched":28}},
				{"node":{"id":1,"title":"Cowboy Bebop","num_episodes":26},
				 "list_status":{"status":"watching","score":0,"num_episodes_watched":5,"is_rereading":true}}
			],"paging":{"next":"%s/users/@me/animelist?offset=500&limit=500"}}`, malAPI)
			return
		}
		fmt.Fprint(w, `{"data":[{"node":{"id":2,"title":"Planned"},"list_status":{"status":"plan_to_watch"}}],"paging":{}}`)
	}))
	defer srv.Close()
	target, _ := url.Parse(srv.URL)

	m := NewMAL(MALConfig{ClientID: "c"})
	m.http = &http.Client{Transport: redirect{target}}

	entries, err := m.ListLibrary(context.Background(), Auth{AccessToken: "tok"}, "anime", nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 3 {
		t.Fatalf("want 3 entries over 2 pages, got %d", len(entries))
	}
	f := entries[0]
	if f.RemoteID != "anime:52991" || f.Status != "completed" || f.Progress != 28 || f.Score != 10 || f.TotalChapters != 28 ||
		f.TitleEnglish != "Frieren: Beyond Journey's End" || f.CoverURL != "https://cdn/f.jpg" || f.URL != "https://myanimelist.net/anime/52991" {
		t.Errorf("frieren = %+v", f)
	}
	if entries[1].Status != "rewatching" {
		t.Errorf("rereading flag should become rewatching, got %q", entries[1].Status)
	}

	only, err := m.ListLibrary(context.Background(), Auth{AccessToken: "tok"}, "anime", []string{"plan_to_watch"})
	if err != nil {
		t.Fatal(err)
	}
	if len(only) != 1 || only[0].RemoteID != "anime:2" {
		t.Errorf("status filter = %+v", only)
	}
}

func TestAniListFavourites(t *testing.T) {
	var toggles []string
	favourite := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Query     string         `json:"query"`
			Variables map[string]any `json:"variables"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(req.Query, "ToggleFavourite"):
			toggles = append(toggles, req.Query)
			favourite = !favourite
			fmt.Fprint(w, `{"data":{"ToggleFavourite":{"__typename":"Favourites"}}}`)
		case strings.Contains(req.Query, "isFavourite"):
			fmt.Fprintf(w, `{"data":{"Media":{"type":"ANIME","isFavourite":%t}}}`, favourite)
		case strings.Contains(req.Query, "favourites"):
			if req.Variables["page"].(float64) == 1 {
				fmt.Fprint(w, `{"data":{"Viewer":{"favourites":{"anime":{"pageInfo":{"hasNextPage":true},"nodes":[{"id":1},{"id":2}]}}}}}`)
			} else {
				fmt.Fprint(w, `{"data":{"Viewer":{"favourites":{"anime":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":3}]}}}}}`)
			}
		default:
			http.Error(w, "unexpected query", http.StatusBadRequest)
		}
	}))
	defer srv.Close()
	orig := anilistAPI
	anilistAPI = srv.URL
	defer func() { anilistAPI = orig }()

	a := NewAniList("x")
	auth := Auth{AccessToken: "tok"}
	ids, err := a.Favourites(context.Background(), auth, "anime")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(ids, ",") != "1,2,3" {
		t.Errorf("favourites = %v", ids)
	}

	if err := a.SetFavourite(context.Background(), auth, "154587", true); err != nil {
		t.Fatal(err)
	}
	if err := a.SetFavourite(context.Background(), auth, "154587", true); err != nil {
		t.Fatal(err)
	}
	if len(toggles) != 1 || !strings.Contains(toggles[0], "animeId") {
		t.Errorf("want a single animeId toggle, got %v", toggles)
	}
}
