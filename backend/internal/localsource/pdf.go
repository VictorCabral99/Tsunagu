package localsource

import (
	"context"
	"strconv"

	"github.com/pdfcpu/pdfcpu/pkg/api"
)

func pdfPageNames(pdfPath string) ([]string, error) {
	n, err := api.PageCountFile(context.Background(), pdfPath)
	if err != nil {
		return nil, err
	}
	names := make([]string, n)
	for i := range names {
		names[i] = strconv.Itoa(i + 1)
	}
	return names, nil
}
