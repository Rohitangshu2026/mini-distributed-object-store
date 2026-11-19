---
status: accepted (2026-11-19)
---

# Uploads use Servlet multipart staging, not a streaming parser

The brief requires `multipart/form-data` uploads. `CLAUDE.md` requires that the controller never holds a whole object in memory.

We use Spring MVC's built-in multipart support, which is Tomcat's Servlet `Part` API. Tomcat parses the whole request before the handler reads it and writes the `file` part to a temp file (`file-size-threshold=0`). The controller then reads that file back one `CHUNK_SIZE` piece at a time. Memory stays bounded at one chunk per upload. No dependency is added.

## Settings

| Setting | Value | Why |
|---|---|---|
| `spring.servlet.multipart.resolve-lazily` | `true` | The handler takes the lazy `MultipartHttpServletRequest` and checks for 2 ALIVE workers before touching a part, so a 503 never stages the body |
| `max-file-size`, `max-request-size` | `-1` | No artificial limit; free staging space is the real limit |
| `location` | `/tmp/objstore-uploads` | Wiped at startup, before the web server starts, to remove files left by a crash |

## Considered options

- **Apache Commons FileUpload 2 streaming API.** It would pipeline chunks to workers as bytes arrive, with nothing staged. Rejected because `commons-fileupload2-jakarta-servlet6` has only milestone releases (newest 2.0.0-M5, February 2026). A milestone does not belong on the core upload path.
- **Writing our own streaming multipart parser.** Parsing untrusted input at a trust boundary is exactly where hand-written code goes wrong. Rejected.
- **Spring WebFlux `PartEvent` streaming.** It needs the reactive stack (decision 0002).

## Consequences

- The controller's temp disk must hold every upload in progress, so free staging space limits the largest object.
- Writes to workers start only after the whole upload has arrived, so time to 201 is roughly receive time plus distribute time.
- The whole request is available before any chunk is written, so custom metadata is validated first and the order of parts does not matter.
- Spring deletes each staged file when its request ends; startup removes anything a crash left behind.
- If a stable streaming parser becomes available, only how `UploadService` obtains the `file` part's `InputStream` changes; the chunking loop already reads a stream.
