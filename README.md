# RYDE

A minimal Android fitness tracking app for cycling, running, and walking. Tracks routes with GPS, shows a live map, and keeps a history of past rides.

## Installing

Download the latest APK from the [Releases](https://github.com/mavenried/ryde/releases) page and install it directly. A new prerelease is published automatically on every push to `master`. The app will notify you when a newer version is available and handle the download and install for you.

## Features

- **Live tracking** — real-time GPS speed/pace, distance, elapsed time, and calorie estimate (physics-based: rider + bike weight, speed, and grade)
- **Fullscreen ride view** — drag the nub up on the ride panel for a big-font, map-free stats display; drag down to return to the map
- **Three activity types** — cycling (speed), running & walking (pace)
- **Google Maps** — live route drawn on a map with dark mode support
- **Auto-pause** — pauses automatically when you stop moving, resumes when you start again
- **Lap splits with audio cues** — spoken announcements at every km/mile with speed or pace
- **Goals** — set a distance or duration target; the app announces when you hit it
- **Trip tags** — label rides with custom tags for easy filtering
- **Heatmap** — overlay all your routes on a single map to see where you ride most
- **Weekly summary** — notification every Monday with your totals for the week
- **Personal records** — longest ride, fastest speed, best pace, and more, with route links
- **Stats charts** — weekly and monthly breakdowns of distance, duration, and calories
- **History** — scrollable list of past routes with stats and a speed-coloured route replay
- **Elevation profile** — chart of altitude over distance, corrected against DEM data after each ride for accurate gain/loss
- **GPX export** — share any saved route as a standard `.gpx` file
- **Music controls** — playback and volume controls sourced from the notification listener
- **Home screen widget** — glanceable live stats while riding
- **Short-ride guard** — confirmation before saving a ride under 1 minute or 0.1 km
- **Metric / imperial** — toggle in settings; all displayed values and audio cues switch units
- **Theme** — light, dark, or follow system; automatically switches to light while riding if preferred
- **In-app updates** — checks GitHub on launch and offers a one-tap download and install

## Requirements

- Android 8.0+ (API 26)
- A Google Maps API key (see setup below)
- Location permission (fine + background for continuous tracking)
- Notification listener permission (for music controls)

## Setup

1. Clone the repo.
2. Create `local.properties` in the project root (it is gitignored) and add your Maps API key:
   ```
   MAPS_API_KEY=your_key_here
   ```
3. Open in Android Studio, sync Gradle, and run.

For CI builds, add `MAPS_API_KEY` as a repository secret (Settings → Secrets and variables → Actions).

## CI / releases

Every push to `master` triggers a GitHub Actions workflow that builds a signed release APK and publishes it as a prerelease tagged `v1.0.<run_number>`. The version is stamped into `BuildConfig.VERSION_NAME` at build time so the in-app update checker can compare it correctly.

## Tech stack

| Layer      | Library                             |
| ---------- | ----------------------------------- |
| UI         | Jetpack Compose + Material 3        |
| Maps       | Google Maps SDK 18 + maps-compose 6 |
| Navigation | Navigation Compose                  |
| Database   | Room 2.6                            |
| Location   | FusedLocationProviderClient         |
| Media      | NotificationListenerService         |
| Widget     | Glance 1.1                          |
| Background | WorkManager 2.10                    |
| SVG        | AndroidSVG 1.4                      |
| Language   | Kotlin 2.0 / coroutines             |

## Project structure

```
app/src/main/kotlin/me/mavenried/Ryde/
├── data/           Room entities, DAOs, AppDatabase
├── domain/         Clean models, RouteRepository, TrackStats
├── service/        TrackingService (foreground), MediaListenerService, WeeklySummaryWorker, DemCorrectionWorker
├── ui/
│   ├── components/ Reusable composables (map views, panels, charts, UpdateDialog)
│   ├── screens/    Home, History, RouteDetail, Settings, Heatmap, Stats, PersonalRecords
│   ├── viewmodel/  TrackingViewModel, HistoryViewModel, RouteDetailViewModel, and more
│   └── theme/      Material 3 colour scheme and typography
├── util/           GpxExporter, ReverseGeocoder, ElevationClient, UserPrefs, UpdateChecker, UpdateInstaller
└── widget/         RydeWidget (Glance)
```
