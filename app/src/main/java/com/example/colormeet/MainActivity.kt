package com.example.colormeet

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.*
import com.google.android.gms.location.LocationServices
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.File

val ColorMap = mapOf(
    "Czerwony" to 0xFFE53935,
    "Zielony" to 0xFF43A047,
    "Niebieski" to 0xFF1E88E5,
    "Zolty" to 0xFFFDD835,
    "Fioletowy" to 0xFF8E24AA,
    "Pomaranczowy" to 0xFFF4511E
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        setContent { MaterialTheme { ColorMeetApp() } }
    }
}

@Composable
fun ColorMeetApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToWaitingRoom = { pin, name, host -> navController.navigate("waiting/$pin/$name/$host") }
            )
        }
        composable("waiting/{pin}/{name}/{host}") { entry ->
            val pin = entry.arguments?.getString("pin") ?: ""
            val name = entry.arguments?.getString("name") ?: ""
            val isHost = entry.arguments?.getString("host")?.toBoolean() ?: false

            WaitingRoomScreen(pin, name, isHost, onStartGame = { colorName ->
                navController.navigate("game/$pin/$colorName") { popUpTo("home") { inclusive = false } }
            })
        }
        composable("game/{pin}/{colorName}") { entry ->
            val pin = entry.arguments?.getString("pin") ?: ""
            val colorName = entry.arguments?.getString("colorName") ?: "Czerwony"
            val colorVal = ColorMap[colorName] ?: 0xFFE53935

            GameScreen(pin = pin, assignedColor = Color(colorVal), onFinishGame = {
                navController.navigate("map/$pin") // Przekazujemy PIN do mapy!
            })
        }
        // Mapa odbiera PIN
        composable("map/{pin}") { entry ->
            val pin = entry.arguments?.getString("pin") ?: ""
            MapScreen(pin = pin, onBackToLobby = {
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            })
        }
    }
}

@Composable
fun HomeScreen(onNavigateToWaitingRoom: (String, String, Boolean) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var playerName by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Color Meet", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = playerName, onValueChange = { playerName = it }, label = { Text("Twoje imię") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = pinInput, onValueChange = { if (it.length <= 4) pinInput = it }, label = { Text("PIN pokoju (dołącz)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (playerName.isNotBlank() && pinInput.length == 4) {
                    isLoading = true
                    db.collection("rooms").document(pinInput).get().addOnSuccessListener { doc ->
                        if (doc.exists() && doc.getString("status") == "LOBBY") {
                            db.collection("rooms").document(pinInput).update("players", FieldValue.arrayUnion(playerName))
                                .addOnSuccessListener {
                                    isLoading = false
                                    onNavigateToWaitingRoom(pinInput, playerName, false)
                                }
                        } else {
                            isLoading = false
                            Toast.makeText(context, "Pokój nie istnieje!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading
        ) { Text("DOŁĄCZ DO GRY") }

        Text("— lub —", modifier = Modifier.padding(vertical = 16.dp))

        OutlinedButton(
            onClick = {
                if (playerName.isNotBlank()) {
                    isLoading = true
                    val newPin = (1000..9999).random().toString()
                    val roomData = hashMapOf(
                        "status" to "LOBBY",
                        "players" to listOf(playerName),
                        "colors" to emptyMap<String, String>(),
                        "photos" to emptyList<String>() // Nowa lista na zaszyfrowane zdjęcia
                    )
                    db.collection("rooms").document(newPin).set(roomData).addOnSuccessListener {
                        isLoading = false
                        onNavigateToWaitingRoom(newPin, playerName, true)
                    }
                } else { Toast.makeText(context, "Podaj imię!", Toast.LENGTH_SHORT).show() }
            }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading
        ) { Text("STWÓRZ NOWY POKÓJ") }
    }
}

@Composable
fun WaitingRoomScreen(pin: String, playerName: String, isHost: Boolean, onStartGame: (String) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var players by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("LOBBY") }

    DisposableEffect(pin) {
        val listener = db.collection("rooms").document(pin).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) {
                players = snap.get("players") as? List<String> ?: emptyList()
                status = snap.getString("status") ?: "LOBBY"

                if (status == "PLAYING") {
                    val colorsMap = snap.get("colors") as? Map<String, String>
                    colorsMap?.get(playerName)?.let { onStartGame(it) }
                }
            }
        }
        onDispose { listener.remove() }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Poczekalnia", style = MaterialTheme.typography.headlineLarge)
        Text("PIN: $pin", style = MaterialTheme.typography.headlineMedium, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
        Text("Gracze:", style = MaterialTheme.typography.titleLarge)
        players.forEach { Text(text = "👤 $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp)) }
        Spacer(Modifier.weight(1f))

        if (isHost) {
            Button(
                onClick = {
                    val availableColors = ColorMap.keys.toList().shuffled()
                    val colorAssignments = mutableMapOf<String, String>()
                    players.forEachIndexed { index, name -> colorAssignments[name] = availableColors[index % availableColors.size] }
                    db.collection("rooms").document(pin).update("status", "PLAYING", "colors", colorAssignments)
                },
                modifier = Modifier.fillMaxWidth().height(60.dp), enabled = players.isNotEmpty()
            ) { Text("ROZPOCZNIJ GRĘ") }
        } else {
            Text("Czekam na hosta...", color = Color.Gray)
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun GameScreen(pin: String, assignedColor: Color, onFinishGame: () -> Unit) {
    val context = LocalContext.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    var isUploading by remember { mutableStateOf(false) }
    var hasPerms by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerms = it[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) { if (!hasPerms) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPerms) {
            CameraPreview(imageCapture)
            Icon(Icons.Default.Add, "Celownik", tint = Color.White.copy(0.5f), modifier = Modifier.size(64.dp).align(Alignment.Center))

            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = Color.Black.copy(0.6f)) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Znajdź:", color = Color.White)
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.size(40.dp).background(assignedColor, CircleShape))
                    }
                }

                if (isUploading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(bottom = 32.dp))
                } else {
                    Button(
                        onClick = {
                            isUploading = true
                            val file = File(context.cacheDir, "photo_temp.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()

                            imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    try {
                                        // 1. Zmniejszamy obrazek
                                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                        val maxDim = 400f // Skalujemy do max 400 pikseli, by zmieścić się w limicie
                                        val scale = maxDim / maxOf(bitmap.width, bitmap.height)
                                        val newWidth = (bitmap.width * scale).toInt()
                                        val newHeight = (bitmap.height * scale).toInt()
                                        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

                                        // 2. Zamieniamy na tekst (Base64)
                                        val baos = ByteArrayOutputStream()
                                        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos) // Jakość 50%
                                        val base64String = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                                        // 3. Wrzucamy do darmowej Bazy Danych
                                        FirebaseFirestore.getInstance().collection("rooms").document(pin)
                                            .update("photos", FieldValue.arrayUnion(base64String))
                                            .addOnSuccessListener {
                                                isUploading = false
                                                onFinishGame()
                                            }.addOnFailureListener {
                                                isUploading = false
                                                Toast.makeText(context, "Błąd Firestore", Toast.LENGTH_SHORT).show()
                                            }
                                    } catch (e: Exception) {
                                        isUploading = false
                                        Toast.makeText(context, "Błąd kompresji", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                override fun onError(exc: ImageCaptureException) { isUploading = false }
                            })
                        },
                        modifier = Modifier.size(80.dp).padding(bottom = 16.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) { Box(Modifier.fillMaxSize().background(Color.Transparent)) }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(imageCapture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                } catch (e: Exception) { e.printStackTrace() }
            }, executor)
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun MapScreen(pin: String, onBackToLobby: () -> Unit) {
    var photosList by remember { mutableStateOf(listOf<ImageBitmap>()) }

    // Nasłuchujemy zmian w pokoju - każde zrobione zdjęcie od razu pojawi się u wszystkich
    LaunchedEffect(pin) {
        FirebaseFirestore.getInstance().collection("rooms").document(pin)
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    val base64Photos = snap.get("photos") as? List<String> ?: emptyList()
                    // Odszyfrowujemy tekst z powrotem na BitMapy
                    photosList = base64Photos.mapNotNull { b64 ->
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (e: Exception) { null }
                    }
                }
            }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212)), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kolaż Graczy", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(vertical = 32.dp))

        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(photosList) { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Zrobione zdjęcie",
                    modifier = Modifier.padding(4.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Button(onClick = onBackToLobby, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("WRÓĆ DO MENU") }
    }
}