package net.cheeseverse.shutterlink

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat.startActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.cheeseverse.shutterlink.ui.theme.ShutterlinkTheme
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.UUID

var curIndex = 0;
var numPicsOnCamera = 0;
var requestedPages = -1;
var picReceiverConnectionStage = 0;
var deviceIPAddress = ""
var cameraIPAddress = ""
val imagesPerRequest = 21
var deviceUUID = "4D454930-0100-1000-8001-"
var cameraMode = ""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            initBox(this);
        }
        //initialize the uuid
        val sharedPrefs = this.getPreferences(Context.MODE_PRIVATE)
        deviceUUID = sharedPrefs.getString("persistUUID", deviceUUID + UUID.randomUUID().toString().substring(24).uppercase())!!
        if(!sharedPrefs.contains("persistUUID")) {
            with (sharedPrefs.edit()) {
                putString("persistUUID", deviceUUID)
                commit()
            }
        }
        startActivity(this, Intent(Settings.Panel.ACTION_WIFI), null)

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if(cameraMode != "" && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            GlobalScope.launch { captureImage() }
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        applicationContext.cacheDir.deleteRecursively()
    }
}


suspend fun doKeepAlive() {
    while(true) {
        try {
            val url = URL("http://$cameraIPAddress:80/cam.cgi?mode=getstate")
//            val con = url.openConnection() as HttpURLConnection
//            delay(100);
//            if (con.responseCode != 200) {
//                println("Keepalive Failed with Message: " + con.responseMessage)
//            }
            val text = url.readText()
            cameraMode = text.substring(text.indexOf("<cammode>") + 9, text.indexOf("</cammode>"))
            println(cameraMode)
//            con.disconnect();
            delay(500)
        } catch(e: Exception) {
            println("Keepalive failed catastrophically: $e")
            requestedPages = -1
            picReceiverConnectionStage = 0
            cameraIPAddress = ""
            deviceIPAddress = ""
            break
        }
    }
}

suspend fun getCameraIntoPlaybackMode() {
    GlobalScope.launch { ackDiscoveryPackets() } //Discover the camera
    while(cameraIPAddress == "") {}
    println("Pairing and connecting")
    withContext(Dispatchers.IO) {
        var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=accctrl&type=req_acc&value=0&value2=Shutterlink")
        var con = url.openConnection() as HttpURLConnection
        delay(250);
        println("First Request: " + con.responseMessage)
        con.disconnect();

        url = URL("http://$cameraIPAddress:80/cam.cgi?mode=getsetting&type=pa")
        con = url.openConnection() as HttpURLConnection
        delay(250);
        println("Second Request: " + con.responseMessage)
        con.disconnect();

        url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=playmode")
        con = url.openConnection() as HttpURLConnection
        delay(250);
        println("Third Request: " + con.responseMessage)
        con.disconnect();
    }
    GlobalScope.launch { doKeepAlive() }
}

suspend fun getAvailablePictures() {
    getCameraIntoPlaybackMode() //first get the camera ready
    withContext(Dispatchers.IO) {
        println("Requesting Available Images")
        val url = URL("http://$cameraIPAddress:60606/Server0/CDS_control")
        val con = url.openConnection() as HttpURLConnection
        con.requestMethod = "POST"  // optional default is GET
        con.setRequestProperty("Content-Type", "text/xml");
        con.setRequestProperty(
            "SOAPACTION",
            "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"
        );
        con.doOutput = true;
        con.outputStream.use { os ->
            val body =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>15</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>Shutterlink</pana:X_FromCP></u:Browse></s:Body></s:Envelope>".toByteArray(
                    Charsets.UTF_8
                )
            os.write(body, 0, body.size)
        }
        val data = con.inputStream.bufferedReader().readText();
        numPicsOnCamera = data.substring(data.indexOf("<TotalMatches>") + 14, data.indexOf("</TotalMatches>")).toInt(); //there are x number of pictures on the camera
        curIndex = numPicsOnCamera;
        println("There are $numPicsOnCamera available on the camera")
        requestedPages = 0;
    }
}

suspend fun enumeratePicturesOnCamera(context: Context, indexToGet: Int, thumbnailList: (String) -> Unit){
    var mostRecentIndex = indexToGet;
    if(indexToGet > numPicsOnCamera) {
        mostRecentIndex = numPicsOnCamera; //can't request more than on the camera
    } else if (indexToGet < 0) {
        mostRecentIndex = imagesPerRequest;
    }
    println("Requesting, last index is $mostRecentIndex")
    var fileArray = List<String>(0) {"empty"}; //thumbnail storage
    println("Opening Control Channel")
    val url = URL("http://$cameraIPAddress:60606/Server0/CDS_control")
    val con = url.openConnection() as HttpURLConnection
    con.requestMethod = "POST"  // optional default is GET
    con.setRequestProperty("Content-Type", "text/xml");
    con.setRequestProperty("SOAPACTION", "urn:schemas-upnp-org:service:ContentDirectory:1#Browse");
    con.doOutput = true;
    con.outputStream.use { os ->
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>${mostRecentIndex - imagesPerRequest}</StartingIndex><RequestedCount>$imagesPerRequest</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>Shutterlink</pana:X_FromCP></u:Browse></s:Body></s:Envelope>".toByteArray(Charsets.UTF_8)
        os.write(body, 0, body.size)
    }
    val data = con.inputStream.bufferedReader().readText();
    val picURLs = Regex("http://\\d{2,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+/\\w+\\.JPG").findAll(data).map { url -> url.value }.toList() //get as list
    println("Got image list")
    for(pic in picURLs.reversed()) { //reversed to put the most recent first
        if(pic.contains("/DT")) {
            var url = URL(pic)
            val filename = pic.substring(pic.lastIndexOf("/") + 1)
            val imageData = url.readBytes();
            val tempFile = File.createTempFile(filename, null, context.cacheDir);
            tempFile.appendBytes(imageData)
            fileArray += pic.substring(pic.lastIndexOf("/") + 1)
            con.disconnect()
            tempFile.renameTo(File(context.cacheDir, pic.substring(pic.lastIndexOf("/") + 1)))
            thumbnailList(pic.substring(pic.lastIndexOf("/") + 1))
        }
    }
//    picCon.disconnect()
    curIndex = mostRecentIndex;
}

suspend fun downloadHQImage(path: String, ctx: Context, downloadDialogVisible: (Boolean) -> Unit, downloadProgress: (Float) -> Unit): Uri {
    downloadDialogVisible(true)
    val fileName = "DO" + path.substring(2);
    println(fileName);
    var url = URL("http://$cameraIPAddress:50001/$fileName");
//    var con = url.openConnection() as HttpURLConnection
//    val fileSize = con.getHeaderField("X-FILE_SIZE").toInt()
    var imageData = "".toByteArray()
    var isVideo = false
    try {
        imageData = url.readBytes()
    } catch(e: FileNotFoundException) {
        url = URL("http://$cameraIPAddress:50001/${fileName.substring(0, fileName.length - 3) + "mp4"}");
        imageData = url.readBytes()
        isVideo = true
    }
    val resolver = ctx.contentResolver;
    val info = ContentValues();
    var imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, info);
    if(isVideo) {
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName.substring(0, fileName.length - 3) + "mp4");
        info.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        imageUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, info);
    } else {
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        info.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    }
    info.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + "Shutterlink");
    val os = resolver.openOutputStream(imageUri!!);
    if (os != null) {
        os.write(imageData)
        os.close()
    }
    downloadDialogVisible(false)
    return imageUri
}

suspend fun constantlyAdvertiseSSDP(socket: MulticastSocket) {
    while(picReceiverConnectionStage < 4) {
        val bytes = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "\r\n").toByteArray(Charsets.UTF_8)
        val advPacket =
            DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900)
        socket.send(advPacket)

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: uuid:$deviceUUID\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: upnp:rootdevice\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::upnp:rootdevice\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:service:ConnectionManager:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:service:ConnectionManager:1\r\n" +
                    "\r\n", socket
        )
        delay(4000)
    }
}

suspend fun advertiseSSDPPacket(data: String, socket: MulticastSocket) {
    val bytes = data.toByteArray(Charsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size)
    packet.address = InetAddress.getByName("239.255.255.250")
    packet.port = 1900
    socket.send(packet)
}

suspend fun ackDiscoveryPackets() {
    var gotRequest = false
    var senderPort = -1
    val listenSocket = MulticastSocket(1900)
    listenSocket.joinGroup(InetAddress.getByName("239.255.255.250"))

    GlobalScope.launch { constantlyAdvertiseSSDP(listenSocket) }

    while(!gotRequest) {
        var receivedData = ByteArray(255)
        var receivedPacket = DatagramPacket(receivedData, 255)
        listenSocket.receive(receivedPacket)
        if(receivedPacket.address == InetAddress.getByName(deviceIPAddress)) {
            continue
        }
        val gotText = receivedPacket.data.toString(Charsets.UTF_8)
//        if(gotText.startsWith("M-SEARCH")) {
//            senderPort = receivedPacket.port
////            gotRequest = true;
//            sendUDPPacket("HTTP/1.1 200 OK\r\n" +
//                    "CACHE-CONTROL: max-age=1800\r\n" +
//                    "EXT: \r\n" +
//                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
//                    "SERVER: Shutterlink/1.0/1\r\n" +
//                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
//                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
//                    "\r\n", "$cameraIPAddress", senderPort)
//            println("ACKed a request for directory")
//            gotRequest = true
////            picReceiverConnectionStage = 1
//        } else
        if (gotText.startsWith("HTTP/1.1 200 OK")) {
            gotRequest = true;
            println("Got an OK from the camera")
            cameraIPAddress = receivedPacket.address.hostAddress!!
        }
    }
    listenSocket.leaveGroup(InetAddress.getByName("239.255.255.250"))
}

fun getContentLengthFromStream(ins: InputStream): Int {
    var buf: List<Byte> = List<Byte>(0) {'a'.code.toByte()}
    buf += ins.read().toByte()
    buf += ins.read().toByte()
    while(buf[buf.size-2] != '\r'.code.toByte() && buf[buf.size-1] != '\n'.code.toByte()) {
        buf += ins.read().toByte()
    }
    return buf.dropLast(2).toByteArray().decodeToString().toInt(16)
}

suspend fun imageServer(ctx: Context, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit) {
    val ss = ServerSocket(49153)
    while(true) {
        val socket = ss.accept()
        if(socket.inetAddress.hostAddress != cameraIPAddress || !socket.inetAddress.isSiteLocalAddress) {
            socket.close()
            continue
        }
        downloading(true)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        var s = ""
        while (!s.endsWith("\r\n\r\n")) {
            s += input.read().toChar()
        }
        println("Processing incoming image: \n$s")
        out.write(
            ("HTTP/1.1 100 Continue\r\n" +
                    "Content-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.flush()

        val resolver = ctx.contentResolver;
        val info = ContentValues();
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis().toString());
        info.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        info.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DCIM + File.separator + "Shutterlink Live Transfer"
        );
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, info);
        val os = resolver.openOutputStream(imageUri!!);
        if (os != null) {
            try {
                var toRead = getContentLengthFromStream(input)
                while(toRead > 0) {
                    var dataChunk = input.readNBytes(toRead)
                    os.write(dataChunk)
                    input.skip(2) //skips the \r\n at the end of the chunk
                    toRead = getContentLengthFromStream(input)
                }
            } catch (e: Exception) {
                println("ERROR: $e")
            }
            os.flush()
            os.close()
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.flush()
        downloading(false)
    }
}

suspend fun listenForControlRequests(ctx: Context) {
    val ss = ServerSocket(49152)
    while(true) {
        val socket = ss.accept()
        if(cameraIPAddress == "") {
            cameraIPAddress = socket.inetAddress.hostAddress!!
        }
        var request = ""
        var s = "empty"
        val streamReader = BufferedReader(InputStreamReader(socket.getInputStream()))
        while(s != "") {
            s = streamReader.readLine()
            request += s + "\n"
        }
        println("Server got request from ${socket.inetAddress.hostAddress}: $request")
        val writer = PrintWriter(socket.getOutputStream())
        if(request.startsWith("GET /dms/ddd.xml")) {
            val toSend  = "<?xml version=\"1.0\"?>\r\n" +
                    "  <root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:vli=\"urn:schemas-panasonic-com:vli\">\r\n" +
                    "  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
                    "  <device>\r\n" +
                    "    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>\r\n" +
                    "    <friendlyName>Shutterlink</friendlyName>\r\n" +
                    "    <manufacturer>Panasonic</manufacturer>\r\n" +
                    "    <modelName>LUMIX_UP_SERVER_SMP</modelName>\r\n" +
                    "    <modelNumber>1.00</modelNumber>\r\n" +
                    "    <UDN>uuid:$deviceUUID</UDN>\r\n" +
                    "    <dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>\r\n" +
                    "    <dlna:X_DLNACAP xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">image-upload</dlna:X_DLNACAP>\r\n" +
                    "    <serviceList>\r\n" +
                    "      <service>\r\n" +
                    "        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>\r\n" +
                    "        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>\r\n" +
                    "        <SCPDURL>/dms/sdd_0.xml</SCPDURL>\r\n" +
                    "        <controlURL>/dms/control_0</controlURL>\r\n" +
                    "        <eventSubURL>/dms/event_0</eventSubURL>\r\n" +
                    "      </service>\r\n" +
                    "    </serviceList>\r\n" +
                    "  </device>\r\n" +
                    "</root>"
            writer.write("HTTP/1.1 200 OK\r\n" +
                    "CONTENT-LANGUAGE: ja\r\n" +
                    "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "CONNECTION: close\r\n" +
                    "\r\n" +
                    toSend
            )
            writer.flush()
//            socket.getOutputStream().flush()
        } else if (request.startsWith("POST /dms/control_0")) {
            var intent = Regex("SOAPACTION: .+\"").find(request)!!.value
            intent = intent.substring(intent.indexOf("#") + 1)

            if(intent == "X_GetDLNAUploadProfiles\"") {
                val toSend =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            " <s:Body>\r\n" +
                            "  <u:X_GetDLNAUploadProfilesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                            "   <SupportedUploadProfiles>JPEG_LRG</SupportedUploadProfiles>\r\n" +
                            "  </u:X_GetDLNAUploadProfilesResponse>\r\n" +
                            " </s:Body>\r\n" +
                            "</s:Envelope>"

                writer.write(
                    "HTTP/1.1 200 OK\r\n" +
                            "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "EXT:\r\n" +
                            "SERVER: Shutterlink/1.0/1\r\n" +
                            "CONNECTION: close\r\n" +
                            "\r\n" +
                            toSend
                )
                writer.flush()
                picReceiverConnectionStage = 4
            } else if (intent == "CreateObject\"") {
                s = "empty"
                while(s != "</s:Envelope>") { //this part looks better if you don't look at it....
                    s = streamReader.readLine()
                    request += s + "\n"
                }
                var imageName = Regex("&lt;dc:title&gt;\\w+&lt;/dc:title&gt;").find(request)!!.value
                imageName = imageName.substring(16, imageName.length - 17)
                println(imageName)

                val toSend =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            " <s:Body>\r\n" +
                            "  <u:CreateObjectResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                            "   <ObjectID>1-media-i</ObjectID>\r\n" +
                            "   <Result>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnporg:metadata-1-0/DIDL-Lite&quot;&gt;&lt;item id=&quot;1-media-i&quot; parentID=&quot;i&quot; restricted=&quot;0&quot;&gt;&lt;dc:title&gt;$imageName&lt;/dc:title&gt;&lt;res importUri=&quot;http://$deviceIPAddress:49153/MEDIA-027-i-1&quot; protocolInfo=&quot;*:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_CI=0&quot;/&gt;&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</Result>\r\n" +
                            "  </u:CreateObjectResponse>\r\n" +
                            " </s:Body>\r\n" +
                            "</s:Envelope>"

                writer.write("HTTP/1.1 200 OK\r\n" +
                        "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                        "EXT:\r\n" +
                        "SERVER: Shutterlink/1.0/1\r\n" +
                        "CONNECTION: close\r\n" +
                        "\r\n" + toSend)
                writer.flush()
            }


        }
        socket.close()
    }
}

fun getIPAddress() {
    var addresses = List<InetAddress>(0) {InetAddress.getByName("127.0.0.1")}
    Collections.list(NetworkInterface.getNetworkInterfaces()).filter { net -> !net.isPointToPoint() }.forEach{it -> addresses += it.inetAddresses.toList().filter {add->add.isSiteLocalAddress} }
    addresses.forEach { it->println(it) }
    if(addresses.size > 1) { //Can happen if the user has just transitioned to the camera wifi, or if they somehow have more than one [non-P2P] network connection (like with an ethernet dongle or something)
        if(addresses.filter {add->add.hostAddress.startsWith("192.168.54.")}.size > 0) { //both cameras I have use this subnet for their hotspot, so I'm hopeful that it's consistent.
            deviceIPAddress = addresses.filter {add->add.hostAddress.startsWith("192.168.54")}[0].hostAddress!!
        } else {
            deviceIPAddress = addresses[0].hostAddress!!
        }
    } else {
        deviceIPAddress = addresses[0].hostAddress!!
    }

}

suspend fun registerAsReceiver(ctx: Context, progressText:(String) -> Unit, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit) {
    progressText("Detecting Device IP Address")
    getIPAddress()
    progressText("Searching for Camera")
    GlobalScope.launch { listenForControlRequests(ctx) }
    GlobalScope.launch{ imageServer(ctx, downloading, downloadProgress)}
    GlobalScope.launch { ackDiscoveryPackets() }
    while(cameraIPAddress == "") {}
    progressText("Attempting to pair, check camera")
    try {
        var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=accctrl&type=req_acc&value=0&value2=Shutterlink")
        var con = url.openConnection() as HttpURLConnection
        delay(125);
        println("Sent Pairing Request to Camera: " + con.responseMessage)
        con.disconnect();
    } catch(e: Exception) {
        println("Failed to send control request: \n$e")
    }
}

fun captureImage() {
    if(cameraMode != "rec") {
        try {
            var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=recmode")
            var con = url.openConnection() as HttpURLConnection
//        delay(125);
            println("Requesting record mode " + con.responseMessage)
            con.disconnect();
        } catch(e: Exception) {
            println("Failed to send control request: \n$e")
        }
    }
        try {
            var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=capture")
            var con = url.openConnection() as HttpURLConnection
//        delay(125);
            println("Asked camera to take a picture " + con.responseMessage)
            con.disconnect();
        } catch (e: Exception) {
            println("Failed to send control request: \n$e")
        }
}

suspend fun downloadThumbnail(context: Context, pic: String, downloadLocation: (String) -> Unit) {
    var url = URL("http://$cameraIPAddress:50001/$pic")
    val imageData = url.readBytes();
    val tempFile = File.createTempFile(pic, null, context.cacheDir);
    tempFile.appendBytes(imageData)
    tempFile.renameTo(File(context.cacheDir, pic))
    downloadLocation(pic)
}

//UI Below
@Composable
fun initBox(ctx: Context) {
    var thumbnails by remember {mutableStateOf(List<String>(0){"empty"})};
    var olderNewerButtonsVisible by remember { mutableStateOf(false)}
    var connectionProgressVisible by remember { mutableStateOf(false) }
    var connectionProgressText by remember { mutableStateOf("Searching for Camera")}
    var downloading by remember { mutableStateOf(false)}
    var downloadProgress by remember { mutableStateOf(0.0f)}
    var connectionMethodSelected by remember { mutableStateOf(false)}
    var connectionToSelect by remember { mutableStateOf(1)}
    var imagePreviewVisible by remember { mutableStateOf(false)}
    var imageToPreview by remember { mutableStateOf("")}

    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.zIndex(10.0f))
        },
    ) { contentPadding ->
        if (downloading) {
            Dialog(onDismissRequest = { /*This dialog can't be dismissed*/ }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(verticalArrangement = Arrangement.SpaceAround) {
                        Row(modifier = Modifier.height(30.dp)) {}
                        Row {
                            Text(
                                text = "Downloading",
                                fontSize = 4.em,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .wrapContentSize(Alignment.Center),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .fillMaxWidth()
                        ) {}
                        Row {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (imagePreviewVisible) {
            Dialog(onDismissRequest = { imagePreviewVisible = false}) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    AsyncImage(model = File(ctx.cacheDir, imageToPreview), contentDescription = "Image Preview")
                }
            }
        }

        if (!connectionMethodSelected) {
            Dialog(onDismissRequest = { /*This dialog can't be dismissed*/ }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }) {
                                    connectionToSelect = 1
                                }
                                .background(if (connectionToSelect == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight(0.6f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.downloadicon),
                                    contentDescription = "Download Icon",
                                    tint = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                )
                            }
                            Row {
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column {
                                        Row {
                                            Text(
                                                "Download Images From Camera",
                                                textAlign = TextAlign.Center,
                                                fontSize = 4.em,
                                                color = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            RadioButton(
                                                selected = (connectionToSelect == 1),
                                                onClick = { connectionToSelect = 1 }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }) {
                                connectionToSelect = 2
                            }
                            .background(if (connectionToSelect == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight(0.6f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.camera_transfer),
                                    contentDescription = "Download Icon",
                                    tint = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                )
                            }
                            Row {
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column {
                                        Row {
                                            Text(
                                                "Create Background Link",
                                                textAlign = TextAlign.Center,
                                                fontSize = 4.em,
                                                color = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            RadioButton(
                                                selected = (connectionToSelect == 2),
                                                onClick = { connectionToSelect = 2 })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(450.dp)
                ) {
                    Button(onClick = {
                        if (connectionToSelect == 1) {
                            if (requestedPages < 0) {
                                GlobalScope.launch { //connect, then request the newest page so we don't have a blank page
                                    getAvailablePictures()
                                    try {
                                        enumeratePicturesOnCamera(
                                            ctx,
                                            curIndex + imagesPerRequest,
                                            { newThumbnailList -> thumbnails += newThumbnailList })
                                    } catch (e: Exception) {
                                        println("Unable to request more pictures")
                                    }

                                }
                                olderNewerButtonsVisible = true

                            }
                        } else if (connectionToSelect == 2) {
                            GlobalScope.launch {
                                connectionProgressVisible = true
                                registerAsReceiver(
                                    ctx,
                                    { txt: String -> connectionProgressText = txt },
                                    { dwn -> downloading = dwn },
                                    { dp -> downloadProgress = dp })
                                delay(500)
                                connectionProgressText = "Connection Successful"
                                delay(500)
                            }
                        }

                        connectionMethodSelected =
                            true //runs regardless of the selected connection method
                    }) {
                        Text("Connect")
                    }
                }
            }
        }

        ShutterlinkTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.Top
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PicList(
                        thumbnails,
                        ctx,
                        snackbarHostState,
                        { visible -> downloading = visible },
                        { dp -> downloadProgress = dp },
                        {vis -> imagePreviewVisible = vis},
                        {itm -> imageToPreview = itm});
                } //PicList
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            if (connectionProgressVisible) {
                                Text(
                                    connectionProgressText,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            if (olderNewerButtonsVisible) {
                                OutlinedButton(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(50.dp),
                                    onClick = {
                                        while (requestedPages < 0) {
                                        } //gotta wait for init, might not have happened yet
                                        if (requestedPages >= 0) {
                                            GlobalScope.launch {
                                                thumbnails = List<String>(0) { "" }
                                                try {
                                                    enumeratePicturesOnCamera(
                                                        ctx,
                                                        curIndex + imagesPerRequest,
                                                        { newThumbnailList -> thumbnails += newThumbnailList })
                                                } catch (e: Exception) {
                                                    println("Unable to request more pictures")
                                                }
                                            }
                                            requestedPages += 1
                                        }
                                    }) {
                                    Text("Newer", color = MaterialTheme.colorScheme.onBackground)
                                }

//                                OutlinedButton(
//                                    modifier = Modifier
//                                        .width(100.dp)
//                                        .height(50.dp),
//                                    onClick = {
//                                            GlobalScope.launch {
//                                                try {
//                                                    captureImage()
//                                                } catch (e: Exception) {
//                                                    println("Unable to capture image")
//                                                }
//                                            }
//                                    }) {
//                                    Text("Capture", color = MaterialTheme.colorScheme.onBackground)
//                                }

                                OutlinedButton(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(50.dp),
                                    onClick = {
                                        while (requestedPages < 0) {
                                        } //gotta wait for init, might not have happened yet
                                        if (requestedPages >= 0) {
                                            GlobalScope.launch {
                                                thumbnails = List<String>(0) { "" }
                                                try {
                                                    enumeratePicturesOnCamera(
                                                        ctx,
                                                        curIndex - imagesPerRequest,
                                                        { newThumbnailList -> thumbnails += newThumbnailList })
                                                } catch (e: Exception) {
                                                    println("Unable to request more pictures")
                                                }
                                            }
                                            requestedPages += 1
                                        }
                                    }) {
                                    Text("Older", color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp))
            }

        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PicList(imagePaths: List<String>, ctx: Context, snackbarHostState: SnackbarHostState, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit, imagePreviewVisible: (Boolean) -> Unit, imageToPreview: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        items(imagePaths.size) { index ->
            AsyncImage(
                model = File(ctx.cacheDir, imagePaths[index]),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp
                        )
                    )
                    .wrapContentHeight()
                    .combinedClickable(
                        onLongClick = {
                            GlobalScope.launch {
                                downloadThumbnail(ctx, "DL" + imagePaths[index].substring(2), imageToPreview)
                                imagePreviewVisible(true)
                            }
//                            imageToPreview(imagePaths[index])
                        },
                        onClick = {
                            GlobalScope.launch {
                                val imageUri = downloadHQImage(
                                    imagePaths[index],
                                    ctx,
                                    { visible -> downloading(visible) },
                                    downloadProgress
                                )
                                scope.launch {
                                    val result = snackbarHostState
                                        .showSnackbar(
                                            message = "Content Downloaded",
                                            actionLabel = "Open",
                                            duration = SnackbarDuration.Short,
                                            withDismissAction = true
                                        )
                                    when (result) {
                                        SnackbarResult.ActionPerformed -> {
                                            val intent = Intent(Intent.ACTION_VIEW)
                                            intent.setDataAndType(imageUri, "image/*")
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                            if (intent.resolveActivity(ctx.packageManager) != null) {
                                                startActivity(ctx, intent, null)
                                            }
                                        }

                                        SnackbarResult.Dismissed -> {
                                        }
                                    }
                                }
                            }
                        }
                    )
                    .clip(
                        shape = RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp
                        )
                    ),
                contentScale = ContentScale.Crop
            )
        }
    }
//    })
}