package com.example.webcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class NetworkManager(private val context: Context) {
    private val TAG = "NetworkManager"
    private val SERVICE_TYPE = "_webcam._tcp."
    private val SERVICE_NAME = "LyonWebCam"
    private val MAGIC_NUMBER = 0x4C594F4E // "LYON"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private val sendMutex = Mutex()

    // Server Side: Register Service
    private var registrationListener: NsdManager.RegistrationListener? = null
    fun registerService(port: Int) {
        unregisterService() // Clean up old ones
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {}
            registrationListener = null
        }
    }

    // Client Side: Discover Service
    private var isResolving = false
    fun discoverService(onServiceFound: (String, Int) -> Unit) {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                if (service.serviceName.contains(SERVICE_NAME) && !isResolving) {
                    isResolving = true
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            isResolving = false
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "Service resolved: ${serviceInfo.host.hostAddress}")
                            onServiceFound(serviceInfo.host.hostAddress!!, serviceInfo.port)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    // Server Side: Start listening for frames and audio
    suspend fun startServer(
        onFrameReceived: (String, String, ByteArray) -> Unit,
        onAudioReceived: (String, ByteArray) -> Unit,
        onClientDisconnected: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        registerService(port)
        isRunning = true

        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                
                launch {
                    handleClientConnection(socket, clientIp, onFrameReceived, onAudioReceived)
                    // When handleClientConnection returns, the client is disconnected
                    onClientDisconnected(clientIp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private val clientOutputStreams = mutableMapOf<String, DataOutputStream>()

    private suspend fun handleClientConnection(
        socket: Socket, 
        clientIp: String, 
        onFrameReceived: (String, String, ByteArray) -> Unit,
        onAudioReceived: (String, ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        val inputStream = DataInputStream(socket.getInputStream())
        val outputStream = DataOutputStream(socket.getOutputStream())
        clientOutputStreams[clientIp] = outputStream
        
        var deviceName = "Unknown"
        try {
            Log.d(TAG, "New connection from $clientIp, waiting for handshake...")
            // Handshake: Read device name
            val nameLength = inputStream.readInt()
            if (nameLength in 1..256) {
                val nameBytes = ByteArray(nameLength)
                inputStream.readFully(nameBytes)
                deviceName = String(nameBytes)
                Log.d(TAG, "Handshake success: $deviceName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed from $clientIp: ${e.message}")
        }

        while (isRunning && !socket.isClosed) {
            try {
                val magic = inputStream.readInt()
                if (magic != MAGIC_NUMBER) {
                    Log.e(TAG, "Desync from $deviceName ($clientIp)")
                    break
                }
                
                val type = inputStream.readInt() // 0: Video, 1: Audio
                val size = inputStream.readInt()
                if (size < 0 || size > 5 * 1024 * 1024) break
                
                val buffer = ByteArray(size)
                inputStream.readFully(buffer)
                
                if (type == 0) {
                    Log.d(TAG, "Server received video frame from $deviceName: ${buffer.size} bytes")
                    onFrameReceived(clientIp, deviceName, buffer)
                } else if (type == 1) {
                    onAudioReceived(clientIp, buffer)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client $deviceName disconnected")
                break
            }
        }
        clientOutputStreams.remove(clientIp)
        try { socket.close() } catch (e: Exception) {}
    }

    fun sendCommandToClient(clientIp: String, command: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending command $command to $clientIp")
                clientOutputStreams[clientIp]?.apply {
                    writeInt(command)
                    flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send command: ${e.message}")
            }
        }
    }

    // Client Side: Listen for commands from server
    suspend fun listenForCommands(onCommand: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val inputStream = DataInputStream(clientSocket?.getInputStream())
        while (isRunning && clientSocket?.isClosed == false) {
            try {
                val command = inputStream?.readInt() ?: break
                onCommand(command)
            } catch (e: Exception) {
                break
            }
        }
    }

    // Client Side: Connect and send frames
    private var outputStream: DataOutputStream? = null

    suspend fun connectToServer(host: String, port: Int, deviceName: String) = withContext(Dispatchers.IO) {
        try {
            clientSocket = Socket(host, port)
            outputStream = DataOutputStream(clientSocket!!.getOutputStream())
            isRunning = true
            
            // Handshake: Send device name
            val nameBytes = deviceName.toByteArray()
            outputStream?.writeInt(nameBytes.size)
            outputStream?.write(nameBytes)
            outputStream?.flush()
            
            Log.d(TAG, "Connected to server")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
        }
    }

    suspend fun sendFrame(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            sendMutex.withLock {
                outputStream?.apply {
                    writeInt(MAGIC_NUMBER) // Header
                    writeInt(0)            // Type 0: Video
                    writeInt(data.size)
                    write(data)
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send frame error: ${e.message}")
        }
    }

    suspend fun sendAudio(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            sendMutex.withLock {
                outputStream?.apply {
                    writeInt(MAGIC_NUMBER) // Header
                    writeInt(1)            // Type 1: Audio
                    writeInt(data.size)
                    write(data)
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send audio error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        unregisterService()
        try { serverSocket?.close() } catch (e: Exception) {}
        try { clientSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        clientSocket = null
    }
}
