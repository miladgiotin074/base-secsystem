package pipeline

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"apksigner/internal/zipconfuse"
)

type Options struct {
	WorkDir       string
	OutputDir     string
	UberSignerJar string
	APKEditorJar  string
	JavaBin       string
}

type Result struct {
	Path     string
	FileName string
}

func Process(inputPath string, opt Options) (Result, error) {
	var zero Result
	if opt.JavaBin == "" {
		opt.JavaBin = "java"
	}
	if opt.UberSignerJar == "" {
		opt.UberSignerJar = "uber-apk-signer-1.3.0.jar"
	}
	if opt.APKEditorJar == "" {
		opt.APKEditorJar = "APKEditor.jar"
	}
	if err := os.MkdirAll(opt.WorkDir, 0o755); err != nil {
		return zero, err
	}
	if err := os.MkdirAll(opt.OutputDir, 0o755); err != nil {
		return zero, err
	}

	taskID := fmt.Sprintf("%d_%s", time.Now().Unix(), randHex(6))
	taskDir := filepath.Join(opt.WorkDir, taskID)
	if err := os.MkdirAll(taskDir, 0o755); err != nil {
		return zero, err
	}
	defer os.RemoveAll(taskDir)

	apk := filepath.Join(taskDir, "work.apk")
	if err := copyFile(inputPath, apk); err != nil {
		return zero, fmt.Errorf("copy input: %w", err)
	}

	if _, err := AddDummyFiles(apk, true); err != nil {
		return zero, fmt.Errorf("dummy files: %w", err)
	}
	if err := zipconfuse.Apply(apk, 10); err != nil {
		return zero, fmt.Errorf("zip confuse: %w", err)
	}
	if fileOK(opt.APKEditorJar) {
		protected, err := runAPKEditor(opt.JavaBin, opt.APKEditorJar, apk, taskDir)
		if err != nil {
			return zero, fmt.Errorf("apkeditor: %w", err)
		}
		apk = protected
	}
	if !fileOK(opt.UberSignerJar) {
		return zero, fmt.Errorf("missing signer jar: %s", opt.UberSignerJar)
	}
	signed, err := runUberSigner(opt.JavaBin, opt.UberSignerJar, apk, taskDir)
	if err != nil {
		return zero, fmt.Errorf("sign: %w", err)
	}
	apk = signed

	name := fmt.Sprintf("Greed-team%s.apk", randHex(3))
	out := filepath.Join(opt.OutputDir, name)
	if err := copyFile(apk, out); err != nil {
		return zero, err
	}
	st, err := os.Stat(out)
	if err != nil || st.Size() == 0 {
		_ = os.Remove(out)
		return zero, fmt.Errorf("empty output")
	}
	return Result{Path: out, FileName: name}, nil
}

func runAPKEditor(java, jar, apk, taskDir string) (string, error) {
	out := filepath.Join(taskDir, "protected.apk")
	cmd := exec.Command(java, "-jar", jar, "p", "-i", apk, "-o", out)
	cmd.Dir = taskDir
	b, err := cmd.CombinedOutput()
	if err != nil {
		return apk, fmt.Errorf("%w: %s", err, trimOut(b))
	}
	if !fileOK(out) {
		return apk, fmt.Errorf("apkeditor produced no file: %s", trimOut(b))
	}
	return out, nil
}

func runUberSigner(java, jar, apk, taskDir string) (string, error) {
	cmd := exec.Command(java, "-jar", jar, "--apks", apk, "--out", taskDir, "--allowResign")
	cmd.Dir = taskDir
	b, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%w: %s", err, trimOut(b))
	}
	entries, err := os.ReadDir(taskDir)
	if err != nil {
		return "", err
	}
	base := strings.TrimSuffix(filepath.Base(apk), filepath.Ext(apk))
	var candidates []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		lower := strings.ToLower(name)
		if !(strings.HasSuffix(lower, "-aligned-debugsigned.apk") ||
			strings.HasSuffix(lower, "-aligned-signed.apk") ||
			strings.HasSuffix(lower, "-signed.apk")) {
			continue
		}
		p := filepath.Join(taskDir, name)
		if !fileOK(p) {
			continue
		}
		if strings.HasPrefix(name, base) {
			return p, nil
		}
		candidates = append(candidates, p)
	}
	if len(candidates) > 0 {
		return candidates[0], nil
	}
	return "", fmt.Errorf("signed apk not found after uber-apk-signer: %s", trimOut(b))
}

func fileOK(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.Size() > 0
}

func randHex(n int) string {
	b := make([]byte, (n+1)/2)
	_, _ = rand.Read(b)
	s := hex.EncodeToString(b)
	if len(s) > n {
		return s[:n]
	}
	return s
}

func trimOut(b []byte) string {
	s := strings.TrimSpace(string(b))
	if len(s) > 400 {
		return s[:400]
	}
	return s
}
