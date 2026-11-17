package gg.dekiru.nagori3k

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun AppUi(app: MainActivity) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = if (app.playing != null) Color.Black else MaterialTheme.colorScheme.background) {
            if (app.playing != null) {
                // The game stays where it is; the menu and the options go over it.
                Box(Modifier.fillMaxSize()) {
                    GameScreen(app)
                    when (app.overlay) {
                        "menu" -> GameMenu(app)
                        "settings" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            SettingsScreen(app) { app.overlay = "menu" }
                        }
                    }
                }
            } else when (app.screen) {
                "settings" -> SettingsScreen(app) { app.screen = "library" }
                else -> LibraryScreen(app)
            }
        }
    }
}

@Composable
fun GameScreen(app: MainActivity) {
    // Back (the system gesture or button) and a tap on the picture open the
    // menu; from the menu, Back resumes.
    BackHandler { if (app.overlay == "none") app.openMenu() else if (app.overlay == "settings") app.overlay = "menu" else app.resumeGame() }
    AndroidView(factory = { app.makeGameView() }, modifier = Modifier.fillMaxSize())
    if (app.overlay == "none") {
        // With the on-screen controls showing, only their Menu button opens the menu. Without them a tap
        // brings them back ("auto") or opens the menu ("never").
        if (app.touchShown) AndroidView(
            factory = { TouchControlsView(it, app) },
            update = { it.autoFire = app.settings.autoFire },
            modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { app.onGameTap() } })
    }
}

/** Over a paused game: carry on, change the options, or leave. */
@Composable
fun GameMenu(app: MainActivity) {
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(28.dp).width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Paused", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { app.resumeGame() }, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                OutlinedButton(onClick = { app.overlay = "settings" }, modifier = Modifier.fillMaxWidth()) { Text("Options") }
                OutlinedButton(onClick = { app.stopGame() }, modifier = Modifier.fillMaxWidth()) { Text("Back to library") }
                Text((if (app.touchShown) "Tap the pause button" else if (app.settings.touchControls == "never") "Tap the screen" else "Tap the screen for the touch controls and their pause button") + ", press Back, or press Start and Select together to open this menu.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun TopBar(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
fun LibraryScreen(app: MainActivity) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar("nagori3k") {
            OutlinedButton(onClick = { app.chooseFolder() }) { Text("Choose folder") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { app.screen = "settings" }) { Text("Options") }
        }
        app.updates.banner?.let { UpdateBanner(app, it) }
        app.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
        when {
            !app.hasFileAccess -> Notice(
                "nagori3k needs access to your files to find your games",
                "Your games folder can be anywhere on this device, so the app asks for access to all files. It only reads the folder you choose.",
                "Allow access") { app.askForFileAccess() }
            app.settings.libraryPath.isEmpty() -> Notice(
                "Choose the folder that holds your NUON games",
                "Disc images (.iso, .chd) in that folder and the folders inside it will appear here.",
                "Choose folder") { app.chooseFolder() }
            app.games.isEmpty() -> Notice(
                "No games found",
                "${app.settings.libraryPath}\nnagori3k plays .iso and .chd disc images.",
                "Look again") { app.refreshLibrary() }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(app.games, key = { it.file.path }) { game -> GameTile(app, game) }
            }
        }
    }
}

/** A slim strip under the title, for a newer release. */
@Composable
fun UpdateBanner(app: MainActivity, update: UpdateInfo) {
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("nagori3k ${update.version} is available", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))) }
            }) { Text("Download") }
            TextButton(onClick = { showNotes = true }) { Text("What’s new") }
            TextButton(onClick = { app.updates.skip() }) { Text("Skip this version") }
        }
    }
    if (showNotes) AlertDialog(
        onDismissRequest = { showNotes = false },
        title = { Text("nagori3k ${update.version}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(update.notes.ifEmpty { "No release notes." }) } },
        confirmButton = { TextButton(onClick = { showNotes = false }) { Text("Close") } },
    )
}

@Composable
fun Notice(title: String, detail: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun GameTile(app: MainActivity, game: Game) {
    val image = app.boxArt.image(game)
    Column(Modifier.clickable { app.play(game) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 190.dp, height = 268.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image.asImageBitmap(), contentDescription = game.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(game.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(game.title, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.width(190.dp))
    }
}

// --- Settings

@Composable
fun SettingsScreen(app: MainActivity, onDone: () -> Unit) {
    val s = app.settings
    var tab by remember { mutableStateOf("game") }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { app.capturing = -1; onDone() }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar("Options") {
            FilterChip(selected = tab == "game", onClick = { tab = "game" }, label = { Text("Game") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = tab == "controls", onClick = { tab = "controls" }, label = { Text("Controls") })
            Spacer(Modifier.width(24.dp))
            Button(onClick = { app.capturing = -1; onDone() }) { Text("Done") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 760.dp)) {
            if (tab == "game") {
                Heading("Picture")
                Toggle("Scanlines", s.scanlines) { s.set("scanlines", it) }
                Toggle("Stretch to fill the screen", s.stretch) { s.set("stretchToFill", it) }
                Note("The game draws a 4:3 picture and has no widescreen mode. Stretched, it fills the screen up to 16:9, and everything is wider than it should be.")
                Toggle("Show the frame rate", s.showFrameRate) { s.set("showFrameRate", it) }
                Text("Frame rate limit", modifier = Modifier.padding(top = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in listOf("Uncapped" to 0, "30" to 30, "24" to 24, "20" to 20))
                        FilterChip(selected = s.frameLimit == value, onClick = { s.set("frameLimit", value) }, label = { Text(label) })
                }
                Note("Tempest 3000 plays faster the faster it draws, so the limit also sets the pace of the game.")

                Heading("Tempest 3000")
                Toggle("Skip the “controller configuration has changed” screen", s.skipControllerMessage) { s.set("skipControllerMessage", it) }
                Toggle("Keep high scores", s.keepHighScores) { s.set("keepHighScores", it) }
                Note("The original forgot its high scores when it was switched off. Enter your initials with the d-pad, not the stick.")
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete high scores…") }

                Heading("Updates")
                Toggle("Check for updates when nagori3k opens", s.checkForUpdates) { s.set("checkForUpdates", it) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${app.updates.currentVersion}")
                        app.updates.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    OutlinedButton(onClick = { app.updates.checkNow() }, enabled = !app.updates.checking) { Text("Check now") }
                }
                Note("Once a day nagori3k asks GitHub whether a newer release exists. Nothing about you is sent, and nothing is downloaded: you are only told, and the link is yours to follow.")
            } else {
                Heading("Analogue control")
                Toggle("Turn the claw with the left stick", s.stickEnabled) { s.set("stickEnabled", it) }
                Toggle("Turn the claw with the triggers (L2 left, R2 right)", s.triggersSteer) { s.set("triggersSteer", it) }
                SliderRow("Sensitivity", s.stickSensitivity, 10..400, 10) { s.set("stickSensitivity", it) }
                SliderRow("Weight", s.stickWeight, 0..200, 5) { s.set("stickWeight", it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Presets", Modifier.weight(1f))
                    // The game's own response, nothing added: full push is its top speed, no flywheel.
                    OutlinedButton(onClick = { s.set("stickSensitivity", 100); s.set("stickWeight", 0) }) { Text("T3K") }
                    Spacer(Modifier.width(8.dp))
                    // The feel this app was tuned to.
                    OutlinedButton(onClick = { s.set("stickSensitivity", 140); s.set("stickWeight", 100) }) { Text("Nagori") }
                }
                Toggle("Reverse direction", s.stickReversed) { s.set("stickReversed", it) }
                Note("The stick and the triggers drive a weighted flywheel, like the knob on the arcade Tempest: the heavier it is, the longer the claw keeps moving after you let go. The d-pad always works as well. T3K sets the game’s own response with no flywheel (sensitivity 100%, weight 0%); Nagori sets the feel this app was tuned to (140%, 100%).")

                Heading("Touch controls")
                Text("Show touch controls", modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in listOf("Auto" to "auto", "Always" to "always", "Never" to "never"))
                        FilterChip(selected = s.touchControls == value, onClick = { s.set("touchControls", value) }, label = { Text(label) })
                }
                Note("Auto shows them when no controller is in use, and hides them as soon as a controller is.")
                Toggle("Auto-fire", s.autoFire) { s.set("autoFire", it) }
                Note("Put a thumb down anywhere on the left half of the screen and move it left or right: the claw turns that way for as long as you hold it there, faster the further you go. Sensitivity and Weight above apply as they do to a controller’s stick.")

                // Not Heading(): its rule fills the width, which in a row left the
                // reset button no room, so its label wrapped a letter to a line.
                Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buttons", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { app.capturing = -1; s.resetMapping() }) { Text("Reset to defaults") }
                    }
                    HorizontalDivider()
                }
                for (i in nuonButtonNames.indices) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(nuonButtonNames[i], Modifier.width(140.dp))
                        OutlinedButton(onClick = { app.capturing = if (app.capturing == i) -1 else i }, modifier = Modifier.width(260.dp)) {
                            Text(if (app.capturing == i) "Press a button…" else padKeyNames[s.padMapping[i]] ?: "Key ${s.padMapping[i]}")
                        }
                    }
                }
                Note("Tap a box, then press the controller button to use. With trigger steering on, L2 and R2 turn the claw and do not press the buttons shown for them here.")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the kept high scores?") },
        text = { Text("The table goes back to the one the game starts with. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { app.deleteHighScores(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun Heading(text: String) {
    Column(Modifier.padding(top = 22.dp, bottom = 6.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(140.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((Math.round(it / step) * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("$value%", Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}
