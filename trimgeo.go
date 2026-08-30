//go:build ignore

package main

import (
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

func main() {
	in := flag.String("in", "", "Path to original geosite.dat")
	keep := flag.String("keep", "cn,private", "Site codes to keep (comma separated)")
	out := flag.String("out", "", "Output path for trimmed file")
	show := flag.Bool("show", false, "List all site codes")
	search := flag.String("search", "", "Search domain in geosite entries")
	flag.Parse()

	if *in == "" {
		fmt.Fprintln(os.Stderr, "Error: -in is required")
		flag.Usage()
		os.Exit(1)
	}

	// 1. 加载原始文件
	data, err := os.ReadFile(*in)
	if err != nil {
		panic(err)
	}
	var siteList geodata.GeoSiteList
	if err := proto.Unmarshal(data, &siteList); err != nil {
		panic(err)
	}

	// 2. 根据参数执行不同操作
	switch {
	case *show:
		for _, entry := range siteList.Entry {
			fmt.Println(entry.Code)
		}
		return

	case *search != "":
		for _, entry := range siteList.Entry {
			for _, domain := range entry.Domain {
				if strings.Contains(domain.Value, *search) {
					fmt.Printf("%s: %s\n", entry.Code, domain.Value)
				}
			}
		}
		return

	default:
		if *out == "" {
			fmt.Fprintln(os.Stderr, "Error: -out is required when trimming")
			os.Exit(1)
		}
		// 3. 裁剪
		keepMap := make(map[string]bool)
		for _, c := range strings.Split(*keep, ",") {
			keepMap[strings.ToUpper(strings.TrimSpace(c))] = true
		}
		outList := &geodata.GeoSiteList{
			Entry: make([]*geodata.GeoSite, 0, len(keepMap)),
		}
		for _, entry := range siteList.Entry {
			if keepMap[entry.Code] {
				outList.Entry = append(outList.Entry, entry)
			}
		}
		// 4. 保存
		outData, err := proto.Marshal(outList)
		if err != nil {
			panic(err)
		}
		if err := os.WriteFile(*out, outData, 0644); err != nil {
			panic(err)
		}
		fmt.Printf("Trimmed geosite saved to %s (kept codes: %v)\n", *out, strings.Split(*keep, ","))
	}
}