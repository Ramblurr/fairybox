# Fairybox

Fairybox is a screenless, child-oriented audio player controlled primarily by physical cards and front-panel controls. Adults curate its media, link cards, and configure its behavior through a web interface.

## Device and interaction

**Fairybox**:
The complete audio-player product and experience, including the physical device and the application running it.
_Avoid_: Box, jukebox

**Front panel**:
The physical playback buttons and their LEDs on the Fairybox device. The web front panel mirrors these controls without changing their meaning.
_Avoid_: Control panel, button board

**Card**:
A physical RFID-bearing object that a listener presents to Fairybox. Its shape is not significant; cards and tokens have the same domain role.
_Avoid_: RFID tag, tag, token

**Card UID**:
The stable identifier read from a card and used to distinguish it from other cards.
_Avoid_: Tag UID, RFID UID, RFID number

**Card placement**:
One continuous period during which a card is present at the reader. Removing and presenting the same card begins a new placement.
_Avoid_: Scan, tap

**Card link**:
The association between a card UID and the media path selected for that card.
_Avoid_: Tag mapping, folder mapping

**Linked card**:
A card whose UID has a card link and therefore selects media.
_Avoid_: Known card, recognized card

**Unlinked card**:
A card whose UID has no card link. It can receive child-facing feedback but cannot start card-owned playback.
_Avoid_: Unknown card, new card, empty card

**Card request**:
One attempt, triggered by a card placement, to resolve linked media, prepare a play queue, and begin playback. Re-presenting the same card can create a distinct request.
_Avoid_: RFID request, playback job

**Card-owned playback**:
Main playback started by a card request and associated with that request across every track and track announcement in its play queue.
_Avoid_: RFID playback

**Card removal behavior**:
The selected policy that either pauses card-owned playback or lets it continue when the card that started it is removed.
_Avoid_: Removal action

**Card return behavior**:
The selected policy that either resumes paused card-owned playback at its retained position or starts a new request when the same card returns.
_Avoid_: Reinsertion behavior

**Card identification**:
An interaction mode in which presenting a card speaks a description of its linked contents instead of starting main playback.
_Avoid_: Card ID mode, identify mode

## Media and playback

**Media root**:
The configured top-level directory containing all local media available to Fairybox.
_Avoid_: Media directory, library root

**Media path**:
A location beneath the media root that selects a playable file, media folder, or playlist. Card links and browser-initiated playback both refer to media paths.
_Avoid_: Item path, linked folder

**Media folder**:
A directory beneath the media root whose playable contents can be expanded into a play queue.
_Avoid_: Album folder

**Playlist**:
A saved, ordered media selection represented by a playlist file. It is an input to playback, not the currently installed play queue.
_Avoid_: Play queue, queue

**Track**:
One ordinary playable audio item, together with descriptive metadata such as title, artist, album, and track number.
_Avoid_: Song, file

**Play queue**:
The ordered sequence consumed by main playback. Its user-facing view shows ordinary tracks and omits generated track-announcement audio.
_Avoid_: Playlist

**Queue source**:
The media path from which the current play queue was prepared.
_Avoid_: Current folder

**Current track**:
The ordinary track currently represented to the listener, excluding any generated track-announcement audio that may be playing immediately before it.
_Avoid_: Physical current track

**Media preparation**:
The work of validating a media path, expanding it into playable tracks, and optionally adding track announcements before installing a play queue.
_Avoid_: Parsing, queue build

**Main playback**:
The queue-based audio channel used for stories, music, browser-selected media, and card-owned playback.
_Avoid_: Main player, normal player

**One-shot audio**:
A short sound or speech request that plays independently of main playback and does not replace the play queue.
_Avoid_: Secondary playback, notification player

**System sound**:
A startup or shutdown cue played as one-shot audio at the corresponding Fairybox lifecycle boundary.
_Avoid_: Jingle, sound effect

**Media metadata**:
Descriptive information about a track or media selection, including title, artist, album, and track number.
_Avoid_: Tags

## Feedback and speech

**Card feedback**:
Child-facing visual or spoken responses that explain whether a card is linked, preparing, starting playback, or unable to play. Card feedback is distinct from track announcements, card identification, and system lifecycle feedback.
_Avoid_: RFID feedback

**LED language**:
The visual portion of card feedback: consistent front-panel light patterns that acknowledge a card and communicate preparation, playback start, or a problem.
_Avoid_: LED effects, card feedback lights

**Linked-card acknowledgement**:
The immediate card-feedback signal that confirms Fairybox recognized a linked card and accepted its request.
_Avoid_: Known-card feedback

**Preparing feedback**:
The card-feedback signal shown when media preparation lasts beyond the brief acknowledgement interval.
_Avoid_: Loading state, buffering feedback

**Playback-started acknowledgement**:
The one-time card-feedback signal produced when the current card request first begins opening its play queue.
_Avoid_: Here-we-go animation, opening feedback

**Card playback problem**:
A missing, unreadable, or unexpected failure that prevents the current card request from producing usable playback.
_Avoid_: Player error, RFID error

**Problem speech**:
A child-facing spoken explanation for an unlinked card or card playback problem. It is independently configurable from LED language and track announcements.
_Avoid_: Error TTS

**Track announcement**:
Generated speech placed immediately before an ordinary track to speak its title or other useful metadata. It belongs to main playback but is omitted from the displayed play queue.
_Avoid_: Card identification, problem speech

**Announcement policy**:
The effective decision about whether tracks under a media path receive track announcements, combining the global setting with inherited path-specific choices.
_Avoid_: TTS setting

## Listening and power

**Safety maximum**:
The absolute volume ceiling that Fairybox never exceeds, regardless of the active day or night profile.
_Avoid_: Maximum volume

**Day and night profiles**:
The two scheduled listening profiles that set playback volume limits, system-sound volumes, and front-panel LED brightness for their active periods.
_Avoid_: Themes, modes

**Playback limit**:
The maximum main-playback volume allowed by the currently active day or night profile, still bounded by the safety maximum.
_Avoid_: Volume setting

**Sleep timer**:
A listener-controlled countdown that fades main playback to silence near its deadline and can optionally power off Fairybox afterward.
_Avoid_: Auto shutdown, shutdown timer

**Sleep fade**:
The final phase of a sleep timer in which main-playback volume is progressively reduced to silence before playback stops.
_Avoid_: Fade-out timer

**Auto shutdown**:
An idle policy that powers off Fairybox after the configured period without interaction or active audio. New interaction or audio activity restarts its wait for idle.
_Avoid_: Sleep timer, inactivity sleep

**Idle**:
The ready condition in which neither main playback nor one-shot audio is active. Idle time can arm auto shutdown.
_Avoid_: Stopped

**Warming up**:
The lifecycle phase in which required resources are available but startup feedback has not finished, so normal interaction is not yet accepted.
_Avoid_: Starting, initialized

**Ready**:
The lifecycle phase in which Fairybox accepts normal card, button, web, and timer interaction.
_Avoid_: Running, active

**Cooling**:
The controlled shutdown phase in which Fairybox stops main playback, presents shutdown feedback, and releases resources before carrying out the requested power operation.
_Avoid_: Stopping, cooling down
