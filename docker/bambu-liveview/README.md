# Docker Compose environment for X1C printers

Docker compose environment for X1C Printers to enable liveview.

> [!IMPORTANT]
> REMEMBER to enable `LAN Mode Liveview` on the printer

# MediaMTX Container

**Copy `example - mediamtx.yml` to `mediamtx.yml`**

Enable TCP for WebRTC
```yaml
webrtcLocalTCPAddress: :8189
```

Disable Docker Instance IPs
```yaml
webrtcIPsFromInterfaces: no
```

Enable Proper IP (docker host OR public ip / dns)
```yaml
webrtcAdditionalHosts: [10.0.0.456, my.dynamic.dns.com]
```
To enable access from internet, add your public ip or DNS **OR** to enable access from local lan, add the ip of the docker host.


# Bambu Web

* Download the [latest](https://github.com/TFyre/bambu-farm/releases/latest) version of bambu-web-X.X.X-runner.jar

* `bambu-web-env.txt` is the config file instead of normal `.env`
* Update `compose.yml` and replace `bambu-web-X.X.X-runner.jar` with the correct version

Enable the following in `bambu-web-env.txt`:
```properties
bambu.live-view-url=/_camerastream/

#For Each Printer:
bambu.printers.PRINTER_ID.stream.live-view=true
```

If you have a full custom url for the printer:
```properties
bambu.printers.PRINTER_ID.stream.url=https://my_stream_domain.com/mystream
```

## Uploading bigger files

Add to `.env`:
```properties
quarkus.http.limits.max-body-size=30M
```

also remember to update `reverse-proxy.conf`:
```conf
    location / {
        client_max_body_size 30m;
        proxy_pass http://bambuweb;
```


# Adding your printers

Only printers that stream over **RTSPS** (X1C / X1E / H2D) need anything here - P1/A1 cameras are read by the app
over their JPEG port. Since 2026-10-17 (from upstream TFyre/bambu-farm `ea39ab8`) **mediamtx pulls each RTSPS
printer itself**; there are no per-printer ffmpeg containers any more. Those could freeze with zero frames while
the container looked healthy (`-timeout` only covers the RTSP control socket), and `restart: always` never fired
because ffmpeg never exited. mediamtx has a real read deadline on the media path and reconnects on its own.

1. Copy `example - compose.yml` to `compose.yml`, `example - mediamtx.yml` to `mediamtx.yml` (a verbatim copy of
   upstream mediamtx v1.21.2 - replace it wholesale on a version bump) and `example - mediamtx.env` to
   `mediamtx.env` (set your docker host's LAN IP in `MTX_WEBRTCADDITIONALHOSTS`).
2. Copy `example - printers.list` to `printers.list` and list each RTSPS printer: `<id> <ip> <access-code>`.
   `<id>` is the printer id in the `.env` configuration file (`bambu.printers.<id>.name=...`) and becomes the
   mediamtx path name - one word, no underscores.
3. Run `./build-printers-env.sh` (needs bash, openssl and network access to the printers). It fetches each
   printer's self-signed certificate fingerprint and writes `printers.env`. Re-run it after a printer firmware
   update if its stream stops connecting - a new certificate means a new fingerprint.
4. `docker compose up -d --remove-orphans` (env files are read at startup only).

`printers.list` and `printers.env` contain access codes - keep them out of git.

# Ports required for outside access

| PORT | UDP/TCP | Purpose |
|--|--|--|
|8189|TCP+UDP|Streaming for WebRTC|
|8080|TCP|HTTP for BambuWeb & WebRTC|

# Starting

Start with `docker compose up`

# Links

* https://github.com/bluenviron/mediamtx
* https://docs.linuxserver.io/images/docker-ffmpeg/
