package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"apksigner/internal/pipeline"
)

func main() {
	inPath := flag.String("in", "", "input APK path")
	outPath := flag.String("out", "", "output APK path (optional; defaults to signed_apks/)")
	root := flag.String("root", "", "directory with uber-apk-signer / APKEditor jars (default: this tool dir)")
	flag.Parse()

	if *inPath == "" {
		fmt.Fprintln(os.Stderr, "usage: apksigner -in input.apk [-out out.apk] [-root jarDir]")
		os.Exit(2)
	}
	absIn, err := filepath.Abs(*inPath)
	if err != nil {
		fatal(err)
	}
	toolDir, err := os.Getwd()
	if err != nil {
		fatal(err)
	}
	if *root != "" {
		toolDir, err = filepath.Abs(*root)
		if err != nil {
			fatal(err)
		}
	} else if exe, err := os.Executable(); err == nil {
		toolDir = filepath.Dir(exe)
	}

	opt := pipeline.Options{
		WorkDir:       filepath.Join(toolDir, "temp_apk_signer"),
		OutputDir:     filepath.Join(toolDir, "signed_apks"),
		UberSignerJar: firstExisting(
			filepath.Join(toolDir, "uber-apk-signer-1.3.0.jar"),
			filepath.Join(toolDir, "uber-apk-signer.jar"),
		),
		APKEditorJar: firstExisting(
			filepath.Join(toolDir, "APKEditor.jar"),
		),
		JavaBin: envOr("JAVA_BIN", "java"),
	}

	res, err := pipeline.Process(absIn, opt)
	if err != nil {
		fatal(err)
	}
	final := res.Path
	if *outPath != "" {
		absOut, err := filepath.Abs(*outPath)
		if err != nil {
			fatal(err)
		}
		if err := copyFile(res.Path, absOut); err != nil {
			fatal(err)
		}
		_ = os.Remove(res.Path)
		final = absOut
	}
	fmt.Println(final)
}

func copyFile(src, dst string) error {
	in, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, in, 0o644)
}

func firstExisting(paths ...string) string {
	for _, p := range paths {
		if st, err := os.Stat(p); err == nil && st.Size() > 0 {
			return p
		}
	}
	if len(paths) > 0 {
		return paths[0]
	}
	return ""
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, err.Error())
	os.Exit(1)
}
