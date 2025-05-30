package com.example.diplom

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.*
import com.google.android.gms.location.*
import com.yandex.mapkit.geometry.Point
import androidx.compose.runtime.*
import com.example.diplom.UImain.UploadAvatarRequest
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

class ActiveService : Service() {
    private val TAG = "ActiveService"
    private lateinit var locationManager: LocationManager
    private var socket: Socket? = null
    private var outputStreamWriter: OutputStreamWriter? = null
    private var bufferedReader: BufferedReader? = null
    private var inputStreamReader: InputStreamReader? = null

    private var dataOut: DataOutputStream? = null
    private var dataIn: DataInputStream? = null
    private val serverIp = "82.179.140.18"  // Замените на ваш IP
    private val serverPort = 44021  // Замените на ваш порт
    private var lastKnownLocation: Point? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    var idUser: Int = 0

//    private val _response = MutableStateFlow("")
//    val rresponse: StateFlow<String> = _response

    private val _responseFlow = MutableSharedFlow<String>(replay = 1)
    val responseFlow: SharedFlow<String> = _responseFlow

    // Свойство для привязки с активити
    private val binder = MyBinder()
    inner class MyBinder : Binder() {
        fun getService(): ActiveService = this@ActiveService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Active service created")
        // Инициализация FusedLocationProviderClient для получения местоположения
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Инициализация LocationCallback для получения обновлений местоположения
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                p0.let {
                    for (location in it.locations) {
                        Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}")
                        lastKnownLocation = Point(location.latitude, location.longitude) // Сохраняем последнее местоположение
                        sendLocationToServer() // Отправляем местоположение на сервер
                    }
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Attempting to open socket")
                socket = Socket(serverIp, serverPort)
                Log.d(TAG, "Socket successfully created: ${socket!!.isConnected}")
                outputStreamWriter = OutputStreamWriter(socket!!.getOutputStream())
                inputStreamReader = InputStreamReader(socket!!.getInputStream())
                bufferedReader = BufferedReader(inputStreamReader)
                val os = socket!!.getOutputStream()
                val is_ = socket!!.getInputStream()
                dataOut = DataOutputStream(os)
                dataIn  = DataInputStream(is_)
                val intent = Intent("ACTION_CONNECTION_SUCCESS")
                sendBroadcast(intent)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to open socket: ${e.message}", e)
                val intent = Intent("ACTION_CONNECTION_FAILED")
                intent.putExtra("error_message", "Failed to open socket: ${e.message}")
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}", e)
                val intent = Intent("ACTION_CONNECTION_FAILED")
                intent.putExtra("error_message", "Unexpected error: ${e.message}")
                sendBroadcast(intent)
            }

            // Запускаем задачи на периодическое обновление местоположения и отправку команд
            executor.scheduleAtFixedRate({
                sendLocationToServer()
                // Вы можете добавить другие команды, которые нужно отправить периодически
                //sendCommandFromActivity("Some Command")
            }, 0, 3000, TimeUnit.SECONDS) // Интервал обновления: каждые 10 секунд
        }
    }


    // Публичный метод для получения текущего местоположения
    fun getLocation(): Point? {
        // Проверка разрешений
        Log.d(TAG, "отправка локации в активити")
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "разрешения есть")
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = Point(location.latitude, location.longitude)
                    Log.d(TAG, "Last known location: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.d(TAG, "Location not available")
                }
            }
            return lastKnownLocation
        } else {
            Log.e(TAG, "Location permission not granted")
            return null
        }
    }

    // Публичный метод для отправки команд из активити
    fun sendCommandFromActivity(command: String, typeSql: String, avatar: ByteArray?) {
        coroutineScope.launch {
            val response = sendToServer(command, typeSql, avatar = avatar)
            _responseFlow.emit(response?:"")
            //_response.value = response ?: ""
            //Log.d(TAG, "Response from server: $response")
        }
    }
    fun sendingAvatar(avatar: UploadAvatarRequest){
        val json = GsonBuilder()
            .disableHtmlEscaping()    // чтобы не было \u003d вместо '='
            .create()
            .toJson(avatar)
        outputStreamWriter?.apply {
            write(json)
            write("\n")
            flush()
        }
    }
    private suspend fun sendToServer(command: String, typeSql: String, avatar:ByteArray?): String? {
        return withContext(Dispatchers.IO) {
            try {
                if(socket == null){Log.d(TAG, "socket null")}
                if(socket!!.isClosed){Log.d(TAG, "socket close")}
                if (socket == null || socket!!.isClosed) {
                    Log.e(TAG, "Socket is closed or null, reopening...")
                    // Переоткрытие сокета
                    socket = Socket(serverIp, serverPort)
                    val os = socket!!.getOutputStream()
                    val is_ = socket!!.getInputStream()
                    outputStreamWriter = OutputStreamWriter(os)
                    inputStreamReader = InputStreamReader(is_)
                    bufferedReader = BufferedReader(inputStreamReader)

                    dataOut = DataOutputStream(os)
                    dataIn  = DataInputStream(is_)
                }
                if (typeSql=="send_avatar"){
                    // 1) Формируем JSON-заголовок
                    Log.d(TAG, "send photo")
                    val headerJson = JSONObject().apply {
                        put("type", "upload_avatar")
                        put("user_id", idUser)          // ваш идентификатор
                        put("size", avatar!!.size)         // длина полезных байт
                    }.toString()
                    Log.d(TAG, "header complite")

                    val headerBytes = headerJson.toByteArray(Charsets.UTF_8)

                    // 2) Шлём: [4-байта длина JSON][JSON UTF-8][raw-bytes]
                    dataOut!!.apply {
                        writeInt(headerBytes.size)
                        write(headerBytes)
                        write(avatar)
                        flush()
                    }
                    Log.d(TAG, "Sending compite")

                    // 3) Читаем ответ: сначала 4-байта длина, потом JSON
                    val respLen = dataIn!!.readInt()
                    val respBuf = ByteArray(respLen)
                    dataIn!!.readFully(respBuf)
                    Log.d(TAG, "response - ${respBuf}")
                    return@withContext String(respBuf, Charsets.UTF_8)
                }
                var requestData = RequestData("sql", typeSql, command)
                if(command[0] =='1'){
                    val commandLogin = command.substring(1)
                    requestData = RequestData("sql_login", typeSql, commandLogin)
                }else if(command[0] == '2'){
                    val commandLogin = command.substring(1)
                    requestData = RequestData("sql_login", typeSql, commandLogin)
                }

                val jsonStr = GsonBuilder().disableHtmlEscaping().create().toJson(requestData)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                //Log.d(TAG, "JSON complite: $jsonStr")
                // 2) Шлём: [4-байта длина JSON][JSON UTF-8]
                dataOut!!.writeInt(jsonBytes.size)
                dataOut!!.write(jsonBytes)
                dataOut!!.flush()
                // Сериализуем объект в JSON с помощью Gson
//                val gson = Gson()
//                val json = gson.toJson(requestData)
//
//                // Отправляем JSON
//                outputStreamWriter?.write("$json\n")
//                outputStreamWriter?.flush()
                Log.d(TAG, "JSON sent in sendToServer: $jsonStr")
                // 3) Читаем ответ: [4-байта длина][JSON UTF-8]
                val respLen = dataIn!!.readInt()
                val respBuf = ByteArray(respLen)
                dataIn!!.readFully(respBuf)
                val response = String(respBuf, Charsets.UTF_8)
                //val response = bufferedReader?.readLine()
                Log.d(TAG, "Server response: $response")
                if(command.startsWith("SELECT user_id" ) && !response.isNullOrEmpty()){
                    try {
                        // Преобразуем строку в JSON-массив
                        val jsonObject = JSONObject(response)
                        val dataArray = jsonObject.getJSONArray("data")
                        // Берем первый объект из массива
                        if (dataArray.length() > 0) {
                            // Берем первый объект из массива
                            val userData = dataArray.getJSONObject(0)

                            // Извлекаем user_id и присваиваем переменной
                            idUser = userData.getInt("user_id")
                            Log.d(TAG, "Parsed user_id: $idUser")
                            sendLocationToServer()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing user_id: ${e.message}")
                    }
                }
                response
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command: ${e.message}")
                null
            }
        }
    }
    //CoroutineScope(Dispatchers.IO).launch {
    private fun sendLocationToServer() {
        if(idUser == 0){return}
        coroutineScope.launch {
            if (ActivityCompat.checkSelfPermission(
                    this@ActiveService,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "разрешения есть")
                // Получаем последнее известное местоположение
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    // Если местоположение найдено, сохраняем его в переменную
                    if (location != null) {
                        lastKnownLocation = Point(location.latitude, location.longitude)
                    }
                    Log.d(TAG, "Last known location: ${location?.latitude}, ${location?.longitude}")
                    val data = """
                    UPDATE myusers
                    SET latitude = ${location?.latitude},       
                        longitude = ${location?.longitude},
                        last_login = CURRENT_TIMESTAMP 
                    WHERE user_id = $idUser;    
                """.trimIndent()
                    Log.d(TAG, data)
                    sendDataSafely(data)
                }
            }
        }
    }
    data class UploadAvatarRequest(
        val command: String = "upload_avatar",
        val userId: Int,
        val avatarBase64: String
    )
    data class RequestData(
        val type: String,
        val typeSql:String,
        val command: String
    )
    private fun sendDataSafely(data: String) {
        coroutineScope.launch {
            dataOut?.let {
                synchronized(it) {
                    try {

                        // Создаём объект с SQL запросом и данными
                        val requestData = RequestData("sql", "", data)
                        Log.d(TAG, requestData.toString())
                        // Сериализуем объект в JSON с помощью Gson
                        val jsonStr = GsonBuilder().disableHtmlEscaping().create().toJson(requestData)
                        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                        //Log.d(TAG, "JSON complite: $jsonStr")
                        // 2) Шлём: [4-байта длина JSON][JSON UTF-8]
                        dataOut!!.writeInt(jsonBytes.size)
                        dataOut!!.write(jsonBytes)
                        dataOut!!.flush()

//                        val gson = Gson()
//                        val json = gson.toJson(requestData)
//                        Log.d(TAG, json)
//                        // Отправляем JSON
//                        it.write("$json\n")
//                        it.flush()

                        Log.d(TAG, "Sent JSON in sendDataSafely: $jsonStr")
                        //val response = bufferedReader?.readLine()
                        val respLen = dataIn!!.readInt()
                        val respBuf = ByteArray(respLen)
                        dataIn!!.readFully(respBuf)
                        val response = String(respBuf, Charsets.UTF_8)
                        Log.d(TAG, "Server response in sendDataSafely: $response")
                    } catch (e: Exception) {
                        Log.d(TAG, "Error in sendDataSafely\n${e.message}")
                        e.printStackTrace()
                        Log.e(TAG, "Error in sendDataSafely: ${e.message}", e)
                    }
                }
            }
        }

    }
    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }
    override fun onDestroy() {
        super.onDestroy()
        try {
            socket?.close()  // Закрытие сокета при уничтожении сервиса
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close socket: ${e.message}")
        }
        executor.shutdown()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Service destroyed")
    }
}
