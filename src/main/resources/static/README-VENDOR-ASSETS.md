# Finvanta CBS — Offline Vendor Assets

All vendor assets must be downloaded and placed locally.
**No CDN references allowed** — this is an air-gapped banking network deployment.

## Required Files

### CSS (`src/main/resources/static/css/`)
| File | Source | Version |
|------|--------|---------|
| `bootstrap.min.css` | https://getbootstrap.com/docs/5.3/getting-started/download/ | 5.3.x |
| `bootstrap-icons.css` | https://icons.getbootstrap.com/#install | 1.11.x |
| `datatables.min.css` | https://datatables.net/download/ (Bootstrap 5 styling) | 2.0.x |

### JS (`src/main/resources/static/js/`)
| File | Source | Version |
|------|--------|---------|
| `jquery.min.js` | https://jquery.com/download/ (compressed) | 3.7.x |
| `bootstrap.bundle.min.js` | https://getbootstrap.com/docs/5.3/getting-started/download/ | 5.3.x |
| `datatables.min.js` | https://datatables.net/download/ | 2.0.x |

### Fonts (`src/main/resources/static/fonts/`)
| File | Source |
|------|--------|
| `bootstrap-icons.woff2` | Included in Bootstrap Icons download package |
| `bootstrap-icons.woff` | Included in Bootstrap Icons download package |

**Important:** The `bootstrap-icons.css` file references fonts via relative path.
Ensure the `fonts/` directory contains the woff2/woff files, and update the
`@font-face` `src` URL in `bootstrap-icons.css` if the relative path differs:
```css
@font-face {
  src: url("../fonts/bootstrap-icons.woff2") format("woff2"),
       url("../fonts/bootstrap-icons.woff") format("woff");
}
```

## Quick Setup (one-time)
```bash
cd src/main/resources/static

# Bootstrap 5.3
curl -L -o css/bootstrap.min.css https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css
curl -L -o js/bootstrap.bundle.min.js https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js

# jQuery 3.7
curl -L -o js/jquery.min.js https://code.jquery.com/jquery-3.7.1.min.js

# Bootstrap Icons 1.11
curl -L -o css/bootstrap-icons.css https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css
mkdir -p fonts
curl -L -o fonts/bootstrap-icons.woff2 https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/fonts/bootstrap-icons.woff2
curl -L -o fonts/bootstrap-icons.woff https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/fonts/bootstrap-icons.woff

# DataTables 2.0 (Bootstrap 5 styled)
curl -L -o css/datatables.min.css https://cdn.datatables.net/2.0.8/css/dataTables.bootstrap5.min.css
curl -L -o js/datatables.min.js https://cdn.datatables.net/2.0.8/js/dataTables.min.js
```

After downloading, verify all files are non-empty and the application renders correctly.
