# server-jars

Drop the Minecraft server jar you want the game-server image to run in here, and the
image build **will not compile anything** — it copies your jar straight in.

    # the fast path: no BuildTools, image build is a copy
    cp /path/to/spigot-26.3.jar server-jars/

    docker build -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0 .

Any single `*.jar` in this directory is used as-is, so the image build never compiles
anything. Resolution is **engine-aware**:

| `SERVER_ENGINE` | Looked for, in order |
| --- | --- |
| `spigot` (default) | `spigot-<SPIGOT_REV>.jar`, `spigot.jar`, `server.jar` |
| `paper` | `paper-<PAPER_VERSION>.jar`, `paper.jar`, `server.jar` |

A jar named for the **other** engine is ignored, with a log line. Asking for `paper` while
only a `spigot-*.jar` is present downloads Paper — it does not quietly ship Spigot under a
Paper label. Any other single jar name is taken as deliberate and used for either engine;
if what remains is still ambiguous the build fails rather than guess.

What happens when this directory is empty (the default, since jars are large and are
not committed):

| `SERVER_ENGINE` | Behaviour |
| --- | --- |
| `spigot` (default) | Spigot publishes no prebuilt jar, so it is compiled with the official BuildTools — minutes of build time, but only then. |
| `paper` | Downloaded from the PaperMC API. Paper publishes prebuilt jars, so this never compiles. |

So the order of preference is always: **your jar → Paper download → Spigot compile**.

Jars are deliberately git-ignored: a server jar is ~50–90 MB of someone else's build
output. Pin the exact revision you ship next to the deployment that uses it instead
(e.g. a release asset, an internal artifact bucket, or a layer in your own base image).