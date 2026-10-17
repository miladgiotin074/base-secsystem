package pipeline

import (
	"archive/zip"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

func AddDummyFiles(apkPath string, withMetaINF bool) (string, error) {
	in, err := zip.OpenReader(apkPath)
	if err != nil {
		return apkPath, err
	}
	defer in.Close()

	existing := make(map[string]struct{}, len(in.File))
	for _, f := range in.File {
		existing[f.Name] = struct{}{}
	}

	tmp := apkPath + ".dummy.tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return apkPath, err
	}

	zw := zip.NewWriter(out)
	ok := false
	defer func() {
		_ = zw.Close()
		_ = out.Close()
		if !ok {
			_ = os.Remove(tmp)
		}
	}()

	for _, f := range in.File {
		if err := copyZipEntry(zw, f); err != nil {
			return apkPath, err
		}
	}

	if withMetaINF {
		for _, name := range []string{"META-INF/CERT1.SF", "META-INF/FAKE.SF"} {
			if _, exists := existing[name]; exists {
				continue
			}
			if err := writeRandomEntry(zw, name, 200); err != nil {
				return apkPath, err
			}
		}
	}
	for i := 0; i < 5; i++ {
		name := fmt.Sprintf("assets/dummy_%d.bin", i)
		if _, exists := existing[name]; exists {
			continue
		}
		if err := writeRandomEntry(zw, name, 200); err != nil {
			return apkPath, err
		}
	}

	if err := zw.Close(); err != nil {
		return apkPath, err
	}
	if err := out.Close(); err != nil {
		return apkPath, err
	}
	ok = true
	if err := os.Rename(tmp, apkPath); err != nil {
		_ = os.Remove(apkPath)
		if err2 := os.Rename(tmp, apkPath); err2 != nil {
			return apkPath, err
		}
	}
	return apkPath, nil
}

func writeRandomEntry(zw *zip.Writer, name string, n int) error {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return err
	}
	w, err := zw.Create(name)
	if err != nil {
		return err
	}
	_, err = io.WriteString(w, hex.EncodeToString(buf))
	return err
}

func copyZipEntry(zw *zip.Writer, f *zip.File) error {
	hdr := f.FileHeader
	w, err := zw.CreateHeader(&hdr)
	if err != nil {
		return err
	}
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()
	_, err = io.Copy(w, rc)
	return err
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}
