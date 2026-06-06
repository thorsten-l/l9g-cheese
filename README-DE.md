# cheese — ein Micetro-CLI

[English version → README.md](README.md)

## Einleitung

`cheese` ist eine Spring-Boot-/Java-21-Kommandozeilenanwendung, die mit einem
**Micetro-(Men&Mice-)IPAM**-Server über **JSON-RPC 2.0** kommuniziert. Sie bietet eine
interaktive Shell zum Anzeigen und Verwalten von DNS-Records, IP-Ranges und
DHCP-Einträgen — inklusive Anlegen/Löschen von Records, Umschalten des Enabled-Status
und Löschen von Objekten per Referenz.

Die Anwendung startet einen interaktiven Prompt (`cheese:>`). `help` zeigt eine
Übersicht, `help <kommando>` die Details zu einem einzelnen Kommando.

## Java-Start

Erfordert **Java 21 oder neuer**.

Mit dem Launcher-Wrapper (empfohlen — er ermittelt sein eigenes Verzeichnis, prüft die
Java-Version und wechselt ins App-Verzeichnis, damit `data/` gefunden wird):

```bash
./cheese.sh
```

Oder das Jar direkt starten:

```bash
java --enable-native-access=ALL-UNNAMED -jar lib/cheese.jar
```

Hinweise:

- Die interaktive Shell benötigt ein **echtes Terminal (TTY)** — in einem Terminal
  ausführen, nicht im Ausgabefenster einer IDE.
- Die Laufzeit-Konfiguration wird aus **`data/config.yaml`** relativ zum
  Arbeitsverzeichnis gelesen (siehe *Konfiguration*).
- `--enable-native-access=ALL-UNNAMED` unterdrückt nur eine JLine-Warnung unter JDK 24+.

## Kommandos

### Micetro-Kommandos

| Kommando | Parameter | Beschreibung |
|---|---|---|
| `list-zones` | — | Anzeigenamen aller primären DNS-Zonen auflisten. |
| `list-records` | `--zone <zone>` `[--separate]` `[--show-refs]` | Alle DNS-Records einer Zone auflisten, nach Name sortiert. `--separate` gibt jede View als eigenen Block aus; `--show-refs` ergänzt die Spalten **REF** und **ENABLED** (✅/❌) (SOA-Refs werden leer ausgegeben). |
| `list-ranges` | `[--filter <filter>]` | IP-Ranges auflisten, die der API-Benutzer verwalten darf (Standardfilter `type!=Container`). |
| `list-ipaddrs` | `<net>` *oder* `--net <net>` | A-Records, DHCP-Reservierungen und Leases auflisten, deren IP mit dem IPv4-Präfix beginnt (z. B. `141.41.2.`), nach IP sortiert. |
| `search` | `--zone <zone>` `--name <name>` `[--show-refs]` | Records per Namenspräfix über alle Views einer Zone suchen. `--show-refs` ergänzt die Spalten **REF** und **ENABLED**. |
| `add-a` | `--zone <zone>` `--name <name>` `--ip <ipv4>` `--ttl <ttl>` | Einen A-Record zu allen Refs (Views) einer Zone hinzufügen; gibt die erzeugten Objekt-Refs aus. |
| `remove-a` | `--zone <zone>` `--name <name>` `[--dry]` | Die eigenen (mit `l9g-cheese` getaggten) A-Records eines Namens entfernen. `--dry` zeigt nur eine Vorschau. |
| `add-txt` | `--zone <zone>` `--name <name>` `--data <text>` | Einen TXT-Record zu allen Refs einer Zone hinzufügen. |
| `remove-txt` | `--zone <zone>` `--name <name>` | Die eigenen (mit `l9g-cheese` getaggten) TXT-Records eines Namens entfernen. |
| `add-record` | `--zone <zone>` `--name <name>` `--type <typ>` `--data <data>` `[--ttl <ttl>]` | Einen Record eines beliebigen Typs zu allen Refs hinzufügen. Erlaubte Typen: `A, AAAA, MX, CNAME, TXT, NS, SRV, PTR, CAA, SVCB, HTTPS, TLSA, SSHFP`. Leeres `--ttl` nutzt den Zonen-Default. |
| `remove-objects` | `--obj-refs <ref>,<ref>,...` `[--dry]` | Objekte anhand ihrer Referenzen löschen (z. B. aus `search --show-refs`). Fragt mit y/N nach. ⚠️ Umgeht den `l9g-cheese`-/Zonen-Schutz — kann jedes Objekt löschen. `--dry` zeigt nur eine Vorschau. |
| `enable-objects` | `--obj-refs <ref>,<ref>,...` | Objekte aktivieren (`enabled = true`). |
| `disable-objects` | `--obj-refs <ref>,<ref>,...` | Objekte deaktivieren (`enabled = false`). |

### DNS-Kommandos

| Kommando | Parameter | Beschreibung |
|---|---|---|
| `lookup` | `<fqdn>` *oder* `--fqdn <fqdn>` | Einen FQDN über echtes DNS (JNDI) auflösen — nicht über Micetro. |

### Crypto-Kommandos

| Kommando | Parameter | Beschreibung |
|---|---|---|
| `encrypt` | `--text <text>` | Einen Klartext in einen `{AES256}…`-String für die `config.yaml` verschlüsseln (siehe *Verschlüsselte Passwörter*). |

### Eingebaute Kommandos

| Kommando | Parameter | Beschreibung |
|---|---|---|
| `help` | `[kommando]` | Kommando-Übersicht oder Detailhilfe zu einem Kommando anzeigen. |
| `version` | — | Build-Name, -Version und -Zeit anzeigen. |
| `clear` | — | Bildschirm löschen. |
| `history` | — | Kommando-Historie anzeigen. |
| `script` | `<datei>` | Kommandos aus einer Skriptdatei ausführen. |
| `quit` / `exit` | — | Shell beenden. |

## Konfiguration

Die Laufzeit-Konfiguration liegt in **`data/config.yaml`** (neben Binary/Jar, relativ
zum Arbeitsverzeichnis). Eine Vorlage befindet sich in `data/config-sample.yaml`.

```yaml
logging:
  level:
    root: ERROR
    l9g: WARN

micetro:
  api-url: "https://mm.example.de/mmws/api/v2/JSON"   # Micetro-JSON-RPC-Endpunkt
  server: "localhost"                                  # Micetro-Servername
  login-name: "apiuser"                                # API-Benutzer
  password: "apipassword"                              # API-Passwort ({AES256}… empfohlen)
  unauthorized-as-forbidden: true
  session-cache-ttl: 245                               # Login-Session-Cache, Sekunden
```

| Bereich | Schlüssel | Bedeutung |
|---|---|---|
| `logging.level` | `root` / `l9g` | Log-Level für den Root-Logger und die Anwendung (`l9g`). |
| `micetro` | `api-url` | URL der Micetro-JSON-RPC-API. |
| | `server` | Micetro-Servername, der an `login` übergeben wird. |
| | `login-name` / `password` | API-Zugangsdaten (Passwort verschlüsseln — siehe unten). |
| | `unauthorized-as-forbidden` | Login-Flag, das an Micetro weitergereicht wird. |
| | `session-cache-ttl` | Sekunden, die eine Login-Session zwischengespeichert/wiederverwendet wird. |

Jeder Property-Wert kann mit dem Präfix `{AES256}…` **verschlüsselt** werden; er wird
beim Start entschlüsselt.

## Verschlüsselte Passwörter

Geheimnisse in der `config.yaml` können verschlüsselt abgelegt werden. Beim Start
entschlüsselt die Anwendung jeden Wert mit dem Präfix `{AES256}` über den
AES-256-Schlüssel in **`data/secret.bin`** (32 Byte; der Pfad lässt sich über die
Umgebungsvariable `SECRET_PATH` überschreiben; die Datei wird bei Fehlen automatisch
erzeugt).

So erzeugt man einen verschlüsselten Wert:

1. Die Shell starten und `encrypt` ausführen:

   ```
   cheese:> encrypt --text 'mein-geheimes-passwort'
   "mein-geheimes-passwort" = "{AES256}AbCdEf...=="
   ```

2. Den `{AES256}…`-String in die `config.yaml` übernehmen, z. B.:

   ```yaml
   micetro:
     password: "{AES256}AbCdEf...=="
   ```

> **Wichtig:** Den Wert mit **derselben** `data/secret.bin` verschlüsseln, die die
> Anwendung zur Laufzeit verwendet. Ein `{AES256}`-Wert, der mit einem anderen Schlüssel
> verschlüsselt wurde, lässt den Start mit `AEADBadTagException: Tag mismatch`
> fehlschlagen.
