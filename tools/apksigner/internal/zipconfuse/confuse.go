package zipconfuse

import (
	"encoding/binary"
	"errors"
	"math/rand"
	"os"
)

const (
	sigEOCD   = 0x06054b50
	sigCDFile = 0x02014b50
)

// Apply marks the first N central-directory entries as "encrypted" and
// randomizes method/time. Local headers stay untouched.
func Apply(path string, entryLimit int) error {
	if entryLimit <= 0 {
		entryLimit = 10
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	eocd, err := findEOCD(data)
	if err != nil {
		return err
	}
	cdStart := int(binary.LittleEndian.Uint32(data[eocd+16 : eocd+20]))
	if cdStart < 0 || cdStart >= len(data) {
		return errors.New("invalid central directory offset")
	}
	pos := cdStart
	for i := 0; i < entryLimit; i++ {
		if pos+46 > len(data) {
			break
		}
		if binary.LittleEndian.Uint32(data[pos:pos+4]) != sigCDFile {
			break
		}
		gpbf := binary.LittleEndian.Uint16(data[pos+8 : pos+10])
		binary.LittleEndian.PutUint16(data[pos+8:pos+10], gpbf|0x0001)
		binary.LittleEndian.PutUint16(data[pos+10:pos+12], uint16(rand.Intn(65536)))
		binary.LittleEndian.PutUint16(data[pos+12:pos+14], uint16(rand.Intn(65536)))
		nameLen := int(binary.LittleEndian.Uint16(data[pos+28 : pos+30]))
		extraLen := int(binary.LittleEndian.Uint16(data[pos+30 : pos+32]))
		commentLen := int(binary.LittleEndian.Uint16(data[pos+32 : pos+34]))
		next := pos + 46 + nameLen + extraLen + commentLen
		if next <= pos || next > len(data) {
			break
		}
		pos = next
	}
	return os.WriteFile(path, data, 0o644)
}

func findEOCD(data []byte) (int, error) {
	min := len(data) - (22 + 65535)
	if min < 0 {
		min = 0
	}
	for i := len(data) - 22; i >= min; i-- {
		if binary.LittleEndian.Uint32(data[i:i+4]) == sigEOCD {
			return i, nil
		}
	}
	return 0, errors.New("EOCD not found")
}
