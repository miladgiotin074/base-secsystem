package reliability

import "time"

func AckDelay(attemptsAfterSend int) time.Duration {
	shift := attemptsAfterSend - 1
	if shift < 0 {
		shift = 0
	}
	if shift > 4 {
		shift = 4
	}
	delay := 5 * time.Second * time.Duration(uint(1)<<uint(shift))
	if delay > 60*time.Second {
		return 60 * time.Second
	}
	return delay
}
