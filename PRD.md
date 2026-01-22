# Product Requirements Document (PRD)
**Product Name**: VibeSplit  
**Version**: 1.0  
**Status**: MVP Completed  
**Author**: Gaurav Sharma

---

## 1. 🔍 Problem Statement
In social settings (road trips, studying, commuting), friends often share a single pair of TWS (True Wireless) earbuds to listen to music. However, sharing audio usually means compromising on the choice of music — both users are forced to listen to the same song. There is no native Android solution to route different audio streams to the Left and Right channels of a Bluetooth headset efficiently.

## 2. 🎯 Product Goal
To build a "Dual Audio" music player that enables two users to listen to separate music tracks simultaneously using a single smartphone and one pair of stereo headphones, with an engaging and premium user interface.

## 3. 👤 User Personas
*   **The Road Trippers**: Two friends in the back seat wanting to listen to their own playlists without carrying two devices.
*   **The Study Buddies**: Students sharing earbuds in a library who need different focus tracks.
*   **The Couple**: Partners who want to share the physical connection of earbuds but have different music tastes.

## 4. 📱 Functional Requirements

### 4.1 Core Audio Engine
*   **FR-01**: The system MUST support a "Split Mode" where independent Audio Decoders feed the Left and Right channels of a stereo output.
*   **FR-02**: The system MUST support a "Stereo Mode" (Same Music) where one track is played on both channels.
*   **FR-03**: The system MUST support gapless audio channel routing via raw PCM manipulation (Interleaving).
*   **FR-04**: The system MUST support MP3, WAV, and AAC/M4A file formats.

### 4.2 Playback Controls
*   **FR-05**: Each player (Left/Right) MUST have independent Play, Pause, Next, Previous, Shuffle, and Repeat controls.
*   **FR-06**: Each player MUST have an independent Volume Slider that does not affect the system master volume or the other channel.
*   **FR-07**: Users MUST be able to Seek/Scrub through tracks independently.
*   **FR-08**: Users MUST be able to "Swap" channels (send Left audio to Right and vice versa) instantly.

### 4.3 Library & File Management
*   **FR-09**: The app MUST provide a "Manual Library" mode where users select specific folders to import.
*   **FR-10**: The app MUST handle `MANAGE_EXTERNAL_STORAGE` permissions to bypass Android 11+ Scoped Storage restrictions for user-selected folders.
*   **FR-11**: The app MUST remember the last played track and queue state.

### 4.4 User Interface (UI)
*   **FR-12**: The UI MUST implement a "Glassmorphism" design language (blur, transparency, gradients).
*   **FR-13**: The Player UI MUST visualize playback with rotating Vinyl animations and simulated frequency bars.
*   **FR-14**: A "Mini Player" MUST be visible on the Library screen for quick access.

### 4.5 Background Playback
*   **FR-15**: The app MUST continue playback when minimized (Foreground Service).
*   **FR-16**: The app MUST provide a Rich Media Notification dependent on the current mode (Split vs Stereo).

## 5. 🛡️ Non-Functional Requirements
*   **NFR-01 Performance**: Audio latency between channels should be negligible (<50ms).
*   **NFR-02 Stability**: The app must handle Bluetooth disconnects gracefully (Auto-Pause).
*   **NFR-03 Compatibility**: Min SDK 26 (Android 8.0) to Target SDK 34 (Android 14).
*   **NFR-04 Security**: File access must be explicitly granted by the user via system dialogs.

## 6. 🔮 Future Scope (Post-MVP)
*   **Streaming Integration**: Support for Spotify/YouTube Music APIs (if legally feasible).
*   **EQ Profiles**: Individual Equalizer settings for Left vs Right ears.
*   **WiFi Sharing**: Sync playback across two different phones over WiFi Direct.
*   **Lyric Sync**: Display synchronized lyrics for both tracks.

---
*End of Document*
