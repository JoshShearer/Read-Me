# Extraction fixtures

Saved real pages for `__tests__/extract.test.ts` (R-M14: extraction is tested against saved
pages, never downloaded during tests). Test data only; none of it is bundled into the app.

| File | Source | Retrieved | Status |
|---|---|---|---|
| `weather-gov-lightning.html` | https://www.weather.gov/safety/lightning-science-overview | 2026-10-01 | US government work (National Weather Service), public domain in the US |
| `weather-gov-flood.html` | https://www.weather.gov/safety/flood-turn-around-dont-drown | 2026-10-01 | US government work (National Weather Service), public domain in the US |
| `gutenberg-1342.html` | https://www.gutenberg.org/cache/epub/1342/pg1342-images.html | 2026-10-01 | Jane Austen, *Pride and Prejudice*: public domain; redistributed with the Project Gutenberg header and licence it carries |

Refresh with `scripts/fetch-page-fixtures.sh`.
