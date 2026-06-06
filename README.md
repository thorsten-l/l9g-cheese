# cheese — a Micetro CLI

[Deutsche Version → README-DE.md](README-DE.md)

## Introduction

`cheese` is a Spring Boot / Java 21 command-line application that talks to a
**Micetro (Men&Mice) IPAM** server over **JSON-RPC 2.0**. It provides an interactive
shell to inspect and manage DNS records, IP ranges and DHCP entries — including
adding/removing records, toggling their enabled state and deleting objects by
reference.

The application starts an interactive shell prompt (`cheese:>`). Type `help` for an
overview or `help <command>` for details on a single command.

## Java startup

Requires **Java 21 or newer**.

Using the launcher wrapper (recommended — it locates its own directory, checks the
Java version and changes into the app directory so `data/` is found):

```bash
./cheese.sh
```

Or run the jar directly:

```bash
java --enable-native-access=ALL-UNNAMED -jar lib/cheese.jar
```

Notes:

- The interactive shell needs a **real terminal (TTY)** — run it in a terminal, not
  in an IDE output window.
- Runtime configuration is read from **`data/config.yaml`** relative to the working
  directory (see *Configuration* below).
- `--enable-native-access=ALL-UNNAMED` only silences a JLine warning on JDK 24+.

## Commands

### Micetro commands

| Command | Parameters | Description |
|---|---|---|
| `list-zones` | — | List the display names of all primary DNS zones. |
| `list-records` | `--zone <zone>` `[--separate]` `[--show-refs]` | List all DNS records of a zone, sorted by name. `--separate` prints each view in its own block; `--show-refs` appends the object **REF** and **ENABLED** (✅/❌) columns (SOA refs are blanked). |
| `list-ranges` | `[--filter <filter>]` | List the IP ranges the API user may manage (default filter `type!=Container`). |
| `list-ipaddrs` | `<net>` *or* `--net <net>` | List A records, DHCP reservations and leases whose IP starts with the given IPv4 prefix (e.g. `141.41.2.`), sorted by IP. |
| `search` | `--zone <zone>` `--name <name>` `[--show-refs]` | Search records by name prefix across all views of a zone. `--show-refs` appends the **REF** and **ENABLED** columns. |
| `add-a` | `--zone <zone>` `--name <name>` `--ip <ipv4>` `--ttl <ttl>` | Add an A record to all refs (views) of a zone; prints the created object refs. |
| `remove-a` | `--zone <zone>` `--name <name>` `[--dry]` | Remove the app's own (`l9g-cheese`-tagged) A records for a name. `--dry` previews only. |
| `add-txt` | `--zone <zone>` `--name <name>` `--data <text>` | Add a TXT record to all refs of a zone. |
| `remove-txt` | `--zone <zone>` `--name <name>` | Remove the app's own (`l9g-cheese`-tagged) TXT records for a name. |
| `add-record` | `--zone <zone>` `--name <name>` `--type <type>` `--data <data>` `[--ttl <ttl>]` | Add a record of a given type to all refs. Allowed types: `A, AAAA, MX, CNAME, TXT, NS, SRV, PTR, CAA, SVCB, HTTPS, TLSA, SSHFP`. A blank `--ttl` uses the zone default. |
| `remove-objects` | `--obj-refs <ref>,<ref>,...` `[--dry]` | Delete objects by their references (e.g. from `search --show-refs`). Prompts for y/N confirmation. ⚠️ Bypasses the `l9g-cheese`/zone-permission guards — it can delete any object. `--dry` previews only. |
| `enable-objects` | `--obj-refs <ref>,<ref>,...` | Enable objects (set `enabled = true`). |
| `disable-objects` | `--obj-refs <ref>,<ref>,...` | Disable objects (set `enabled = false`). |

### DNS commands

| Command | Parameters | Description |
|---|---|---|
| `lookup` | `<fqdn>` *or* `--fqdn <fqdn>` | Resolve a FQDN via real DNS (JNDI) — not via Micetro. |

### Crypto commands

| Command | Parameters | Description |
|---|---|---|
| `encrypt` | `--text <text>` | Encrypt a clear-text value into a `{AES256}…` string for `config.yaml` (see *Encrypted passwords*). |

### Built-in commands

| Command | Parameters | Description |
|---|---|---|
| `help` | `[command]` | Show the command overview, or detailed help for one command. |
| `version` | — | Show build name, version and time. |
| `clear` | — | Clear the terminal screen. |
| `history` | — | Show the command history. |
| `script` | `<file>` | Execute commands from a script file. |
| `quit` / `exit` | — | Exit the shell. |

## Configuration

Runtime configuration lives in **`data/config.yaml`** (next to the binary/jar, relative
to the working directory). A template is provided in `data/config-sample.yaml`.

```yaml
logging:
  level:
    root: ERROR
    l9g: WARN

micetro:
  api-url: "https://mm.example.de/mmws/api/v2/JSON"   # Micetro JSON-RPC endpoint
  server: "localhost"                                  # Micetro server name
  login-name: "apiuser"                                # API user
  password: "apipassword"                              # API password ({AES256}… recommended)
  unauthorized-as-forbidden: true
  session-cache-ttl: 245                               # login session cache, seconds
```

| Section | Key | Meaning |
|---|---|---|
| `logging.level` | `root` / `l9g` | Log levels for the root logger and the application (`l9g`). |
| `micetro` | `api-url` | URL of the Micetro JSON-RPC API. |
| | `server` | Micetro server name passed to `login`. |
| | `login-name` / `password` | API credentials (encrypt the password — see below). |
| | `unauthorized-as-forbidden` | Login flag forwarded to Micetro. |
| | `session-cache-ttl` | Seconds a login session is cached/reused. |

Any property value may be **encrypted** with an `{AES256}…` prefix; it is decrypted at
startup.

## Encrypted passwords

Secrets in `config.yaml` can be stored encrypted. At startup the application decrypts
any value prefixed `{AES256}` using the AES-256 key in **`data/secret.bin`** (32 bytes;
the path can be overridden with the `SECRET_PATH` environment variable; it is generated
automatically if missing).

To create an encrypted value:

1. Start the shell and run `encrypt`:

   ```
   cheese:> encrypt --text 'my-secret-password'
   "my-secret-password" = "{AES256}AbCdEf...=="
   ```

2. Copy the `{AES256}…` string into `config.yaml`, e.g.:

   ```yaml
   micetro:
     password: "{AES256}AbCdEf...=="
   ```

> **Important:** encrypt the value with the **same** `data/secret.bin` the application
> uses at runtime. A `{AES256}` value encrypted with a different key fails startup with
> `AEADBadTagException: Tag mismatch`.
