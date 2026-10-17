package zipconfuse

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

func TestApplySetsEncryptedBitOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.apk")
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, name := range []string{"a.txt", "b.txt", "c.txt"} {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte("hello-" + name)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	raw := buf.Bytes()
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		t.Fatal(err)
	}
	methodBefore := cdMethod(t, raw, 0)
	if err := Apply(path, 10); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	eocd, err := findEOCD(data)
	if err != nil {
		t.Fatal(err)
	}
	cd := int(binary.LittleEndian.Uint32(data[eocd+16 : eocd+20]))
	gpbf := binary.LittleEndian.Uint16(data[cd+8 : cd+10])
	if gpbf&0x0001 == 0 {
		t.Fatalf("encrypted bit not set: %#x", gpbf)
	}
	methodAfter := binary.LittleEndian.Uint16(data[cd+10 : cd+12])
	if methodAfter != methodBefore {
		t.Fatalf("compression method changed %d → %d", methodBefore, methodAfter)
	}
}

func cdMethod(t *testing.T, data []byte, index int) uint16 {
	t.Helper()
	eocd, err := findEOCD(data)
	if err != nil {
		t.Fatal(err)
	}
	pos := int(binary.LittleEndian.Uint32(data[eocd+16 : eocd+20]))
	for i := 0; i < index; i++ {
		nameLen := int(binary.LittleEndian.Uint16(data[pos+28 : pos+30]))
		extraLen := int(binary.LittleEndian.Uint16(data[pos+30 : pos+32]))
		commentLen := int(binary.LittleEndian.Uint16(data[pos+32 : pos+34]))
		pos += 46 + nameLen + extraLen + commentLen
	}
	return binary.LittleEndian.Uint16(data[pos+10 : pos+12])
}
