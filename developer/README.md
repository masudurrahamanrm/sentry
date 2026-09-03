# SentrY — Developer Workspace & Tooling

Welcome to the **Developer Version** environment. This folder houses all developer configurations, testing scripts, and operational guides so development remains completely isolated from the production version.

---

## 🌐 Environment Comparison

| Setting | Production Version | Developer / Beta Version |
| :--- | :--- | :--- |
| **Git Branch** | `main` (Locked at `v1.1-production`) | `develop` (Active work) |
| **Backend URL** | `https://sentry-f502.onrender.com/api/v1` | `https://sentry-devloper-version.onrender.com/api/v1` |
| **Database** | MongoDB Atlas: `kinetix_sentry` | MongoDB Atlas: `kinetix_sentry_beta` |
| **Android Package (SentrY)** | `com.example.sentry` | `com.example.sentry.beta` |
| **Android Package (Kinetix)** | `com.example.kinetix` | `com.example.kinetix.beta` |
| **Side-by-Side Install** | — | ✅ Yes (can be installed alongside production) |

---

## 🛠️ Quick Developer Scripts

All scripts are located in `developer/scripts/`:

1. **`build-beta-apk.bat`**  
   Compiles the Beta APKs for both `sentry` and `kinetix` with `.beta` package names and points them to the developer backend.

2. **`check-developer-backend.bat`**  
   Pings `https://sentry-devloper-version.onrender.com/health` and verifies that MongoDB and Cloudflare R2 are healthy.

3. **`run-local-backend.bat`**  
   Launches the local backend on port 4000 using the developer database (`kinetix_sentry_beta`).

---

## 🚀 How to Merge to Production When Ready

When you are done testing all your new features in Beta and want to release them to production:

1. Ensure the Beta build has zero errors and all tests pass.
2. Run `developer/scripts/prepare-production-release.bat` or tell your AI assistant:  
   **"Merge to production"**.
3. The process will:
   - Increment `versionCode` in Gradle (e.g. 2 → 3).
   - Merge `develop` into `main`.
   - Create a new production release tag (e.g. `v1.2-production`).
   - Build the final signed production APKs.
