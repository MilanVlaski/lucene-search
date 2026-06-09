rebuild:
	echo -n "rebuild" | socat - UNIX-CONNECT:/tmp/rebuild_service.sock
