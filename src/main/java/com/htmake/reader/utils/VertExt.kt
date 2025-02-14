package com.htmake.reader.utils

import com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.MongoCollection
import com.google.common.base.Throwables
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging
import com.htmake.reader.entity.BasicError
import com.htmake.reader.entity.MongoFile
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.File
import java.nio.file.Paths
import com.htmake.reader.config.AppConfig
import com.google.gson.reflect.TypeToken
import kotlin.reflect.KProperty1
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.FileUtils

/**
 * @Auther: zoharSoul
 * @Date: 2019-05-21 16:17
 * @Description:
 */
val logger = KotlinLogging.logger {}

val gson = GsonBuilder().disableHtmlEscaping().create()
val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

var storageFinalPath = ""
var workDirPath = ""
var workDirInit = false

fun RoutingContext.success(any: Any?) {
    val toJson: String = if (any is JsonObject) {
        any.toString()
    } else {
        gson.toJson(any)
    }
    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(toJson)
}

fun RoutingContext.error(throwable: Throwable) {
    val path = URLDecoder.decode(this.request().absoluteURI(), "UTF-8")
    val basicError = BasicError(
            "Internal Server Error",
            throwable.toString(),
            throwable.message.toString(),
            path,
            500,
            System.currentTimeMillis()
    )

    val errorJson = gson.toJson(basicError)
    logger.error("Internal Server Error", throwable)
    logger.error { errorJson }

    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .setStatusCode(500)
            .end(errorJson)
}

fun getWorkDir(subPath: String = ""): String {
    if (!workDirInit && workDirPath.isEmpty()) {
        var appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
        if (appConfig != null && appConfig.workDir.isNotEmpty() && !appConfig.workDir.equals(".")) {
            val workDirFile = File(appConfig.workDir)
            if (workDirFile.exists() && !workDirFile.isDirectory()) {
                logger.error("reader.app.workDir={} is not a directory", appConfig.workDir)
            } else {
                if(!workDirFile.exists()) {
                    logger.info("reader.app.workDir={} not exists, creating", appConfig.workDir)
                    workDirFile.mkdirs()
                }
                workDirPath = workDirFile.absolutePath
            }
        }
        if (workDirPath.isEmpty()) {
            var osName = System.getProperty("os.name")
            var currentDir = System.getProperty("user.dir")
            logger.info("osName: {} currentDir: {}", osName, currentDir)
            // MacOS 存放目录为用户目录
            if (osName.startsWith("Mac OS", true) && !currentDir.startsWith("/Users/")) {
                workDirPath = Paths.get(System.getProperty("user.home"), ".reader").toString()
            } else {
                workDirPath = currentDir
            }
        }
        logger.info("Using workdir: {}", workDirPath)
        workDirInit = true
    }
    var path = Paths.get(workDirPath, subPath);

    return path.toString();
}

fun getWorkDir(vararg subDirFiles: String): String {
    return getWorkDir(getRelativePath(*subDirFiles))
}

fun getRelativePath(vararg subDirFiles: String): String {
    val path = StringBuilder("")
    subDirFiles.forEach {
        if (it.isNotEmpty()) {
            path.append(File.separator).append(it)
        }
    }
    return path.toString().let{
        if (it.startsWith("/")) {
            it.substring(1)
        } else {
            it
        }
    }
}

fun getStoragePath(): String {
    if (storageFinalPath.isNotEmpty()) {
        return storageFinalPath;
    }
    var storagePath = ""
    var appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
    if (appConfig != null) {
        storagePath = getWorkDir("storage")
        storageFinalPath = storagePath
    } else {
        // 实际上不会访问到这里
        // app 还没初始化完成，配置还没加载完成 使用当前路径的 storage
        storagePath = File("storage").path
    }

    logger.info("Using storagePath: {}", storagePath)
    return storagePath;
}

fun saveStorage(vararg name: String, value: Any, pretty: Boolean = false) {
    val toJson: String = if (value is JsonObject || value is JsonArray) {
        value.toString()
    } else if (pretty) {
        prettyGson.toJson(value)
    } else {
        gson.toJson(value)
    }

    var storagePath = getStoragePath()
    var storageDir = File(storagePath)
    if (!storageDir.exists()) {
        storageDir.mkdirs()
    }

    val filename = name.last()
    val path = getRelativePath(*name.copyOfRange(0, name.size - 1), "${filename}.json")
    val file = File(storagePath + File.separator + path)
    logger.info("Save file to storage name: {} path: {}", name, file.absoluteFile)

    if (!file.parentFile.exists()) {
        file.parentFile.mkdirs()
    }

    if (!file.exists()) {
        file.createNewFile()
    }
    file.writeText(toJson)
    saveMongoFile(path, toJson)
}

fun getStorage(vararg name: String): String?  {
    var storagePath = getStoragePath()
    var storageDir = File(storagePath)
    if (!storageDir.exists()) {
        storageDir.mkdirs()
    }

    val filename = name.last()
    val path = getRelativePath(*name.copyOfRange(0, name.size - 1), "${filename}.json")
    val file = File(storagePath + File.separator + path)
    logger.info("Read file from storage name: {} path: {}", name, file.absoluteFile)
    if (!file.exists()) {
        return readMongoFile(path)?.also { content ->
            if (content.isNotEmpty()) {
                if (!file.parentFile.exists()) {
                    file.parentFile.mkdirs()
                }
                file.createNewFile()
                file.writeText(content)
            }
        }
    }
    val content = file.readText()
    if (content.isEmpty()) {
        return readMongoFile(path)?.also { content ->
            if (content.isNotEmpty()) {
                if (!file.parentFile.exists()) {
                    file.parentFile.mkdirs()
                }
                file.createNewFile()
                file.writeText(content)
            }
        } ?: content
    }
    return content
}

fun getMongoFileStorage(): MongoCollection<MongoFile>? {
    var appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
    return MongoManager.fileStorage(appConfig.mongoDbName, "storage")
}

fun readMongoFile(path: String): String? {
    if (MongoManager.isInit()) {
        logger.info("Get mongoFile {}", path)
        val doc = getMongoFileStorage()?.find(eq("path", path))?.first();
        if (doc != null) {
            return doc.content
        }
    }
    return null
}

fun saveMongoFile(path: String, content: String): Boolean {
    if (MongoManager.isInit()) {
        logger.info("Save mongoFile {}", path)
        var doc = getMongoFileStorage()?.find(eq("path", path))?.first();
        if (doc != null) {
            doc.content = content
            doc.updated_at = System.currentTimeMillis()
            val result = getMongoFileStorage()?.replaceOne(eq("path", path), doc, ReplaceOptions().upsert(true));
            return if(result != null && result.getModifiedCount() > 0) true else false
        } else {
            doc = MongoFile(path, content)
            try {
                getMongoFileStorage()?.insertOne(doc)
                return true
            } catch(e: Exception) {
                logger.info("Save mongoFile {} failed", path)
                e.printStackTrace()
            }
        }
    }
    return false
}

fun asJsonArray(value: Any?): JsonArray? {
    if (value is JsonArray) {
        return value
    } else if (value is String) {
        return JsonArray(value)
    }
    return null
}

fun asJsonObject(value: Any?): JsonObject? {
    if (value is JsonObject) {
        return value
    } else if (value is String) {
        return JsonObject(value)
    }
    return null
}

//convert a data class to a map
fun <T> T.serializeToMap(): Map<String, Any> {
    return convert()
}

//convert string to a map
fun <T> T.toMap(): Map<String, Any> {
    return convert()
}

//convert a map to a data class
inline fun <reified T> Map<String, Any>.toDataClass(): T {
    return convert()
}

//convert an object of type I to type O
inline fun <I, reified O> I.convert(): O {
    val json = if (this is String) {
        this
    } else {
        gson.toJson(this)
    }
    return gson.fromJson(json, object : TypeToken<O>() {}.type)
}

@Suppress("UNCHECKED_CAST")
fun <R> readInstanceProperty(instance: Any, propertyName: String): R {
    val property = instance::class.memberProperties
                     // don't cast here to <Any, R>, it would succeed silently
                     .first { it.name == propertyName } as KProperty1<Any, *>
    // force a invalid cast exception if incorrect type here
    return property.get(instance) as R
}

@Suppress("UNCHECKED_CAST")
fun setInstanceProperty(instance: Any, propertyName: String, propertyValue: Any) {
    val property = instance::class.memberProperties
                     .first { it.name == propertyName }
    if(property is KMutableProperty<*>) {
        property.setter.call(instance, propertyValue)
    }
}

fun Book.fillData(newBook: Book, keys: List<String>): Book {
    keys.let {
        for (key in it) {
            var current = readInstanceProperty<String>(this, key)
            if (current.isNullOrEmpty()) {
                var cacheValue = readInstanceProperty<String>(newBook, key)
                if (!cacheValue.isNullOrEmpty()) {
                    setInstanceProperty(this, key, cacheValue)
                }
            }
        }
    }
    return this
}

fun getRandomString(length: Int) : String {
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun genEncryptedPassword(password: String, salt: String): String {
    return MD5Utils.md5Encode(
        MD5Utils.md5Encode(password + salt).toString() + salt
    ).toString()
}

fun jsonEncode(value: Any, pretty: Boolean = false): String {
    if (pretty) {
        return prettyGson.toJson(value)
    }
    return gson.toJson(value)
}

/**
 * 列出指定目录下的所有文件
 */
fun File.deepListFiles(allowExtensions: Array<String>?): List<File> {
    val fileList = arrayListOf<File>()
    this.listFiles().forEach { it ->
        //返回当前目录所有以某些扩展名结尾的文件
        if (it.isDirectory()) {
            fileList.addAll(it.deepListFiles(allowExtensions))
        } else {
            val extension = FileUtils.getExtension(it.name)
            if(allowExtensions?.contentDeepToString()?.contains(extension) == true
                || allowExtensions == null) {
                fileList.add(it)
            }
        }
    }
    return fileList
}
