class MainActivity : ComponentActivity() {

    private val mediaPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            permissionGranted = result.values.any { it }
        }

    private var permissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMediaPermissions()

        setContent {
            if (permissionGranted) {
                AppRoot()
            } else {
                PermissionWaitingScreen()
            }
        }
    }

    private fun requestMediaPermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        mediaPermissionLauncher.launch(permissions)
    }
}