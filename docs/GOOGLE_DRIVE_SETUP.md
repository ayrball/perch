# ☁️ Google Drive Cloud Backup Setup Guide for Perch

Perch uploads completed video segments to a `Perch Recordings` folder in your Google Drive using the **Google Drive API v3** and **WorkManager**. It only requests the `drive.file` scope, so it can see just the files and folders it created.

Follow these steps once per Google Cloud project, then connect from the app.

---

## 1. Create a Google Cloud Project
1. Open the [Google Cloud Console](https://console.cloud.google.com/).
2. **Select a project → New Project**, name it `Perch Security Camera`, **Create**.

## 2. Enable Google Drive API
**APIs & Services → Library →** search **Google Drive API → Enable**.

## 3. Configure the OAuth consent screen
1. **APIs & Services → OAuth consent screen**, user type **External**, **Create**.
2. App name `Perch`, plus your email as support and developer contact.
3. **Scopes → Add or remove scopes →** `.../auth/drive.file`.
4. Add the Google account(s) you will back up from under **Test users**. While the app is in *Testing* mode, only listed test users can sign in, and Google may expire their grants periodically (see *Troubleshooting*).

## 4. Create the Android OAuth client
1. **APIs & Services → Credentials → Create credentials → OAuth client ID**, type **Android**.
2. Package name `com.perch.cam`.
3. SHA-1 fingerprint of the key that signs your build. For a debug build:
   ```powershell
   keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```
4. **Create**. No client ID needs to be pasted into the app: Android matches package name + signature.

---

## 5. Connect from the app
**Settings → Cloud Backup → Connect Google Drive**

1. Pick your Google account.
2. Approve the Drive access prompt.
3. Perch makes a real Drive call to confirm it works. Only then does it show **Google Drive connected**, with your account and real storage usage. If anything fails you get the reason right on the screen and nothing is marked as connected.

Backups then run automatically: right after each segment is saved, and every 15/30/60 minutes as a safety net. **WiFi Only** (default) holds uploads until you're on an unmetered network.

## 6. What happens to the files

- Each finished segment (e.g. `PERCH_20260928_193000.mp4`) is recorded in the app's database as `PENDING`, uploaded, then marked `UPLOADED` with its Drive file ID. The **Recordings** screen shows the state of each one.
- **Local storage is capped** (Settings → Local Storage, default 8 GB). With **Delete After Upload** on, the phone copy is removed once it is safely in Drive and the recording stays listed as *Drive only*. If the cap is reached, the oldest already-uploaded files go first, then the oldest not-yet-uploaded ones, so a long outage can lose old footage but never fills the phone.

## Troubleshooting

| What you see | What it means |
|--------------|---------------|
| Notification *Google Drive needs you to sign in* and **Sign-in required** in Settings | Google revoked or expired the grant. Tap **Reconnect**. Uploads pause (nothing is lost) until you do. Apps left in *Testing* mode can have grants expire after a few days: moving the consent screen to *In production* avoids that. |
| *Google rejected the sign-in* when connecting | The OAuth client doesn't match this build: check the package name and the SHA-1 of the key actually used to sign it (debug vs release). |
| Notification *Google Drive is full* | Backups pause until you free space. Local retention keeps the phone from filling in the meantime. |
| Uploads stay pending | Check WiFi Only, that the phone has internet, and the **Last backup** time in Settings. |
