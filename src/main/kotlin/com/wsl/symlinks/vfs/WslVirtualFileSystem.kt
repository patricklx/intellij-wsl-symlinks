package com.wsl.symlinks.vfs

import ai.grazie.utils.WeakHashMap
import com.intellij.ide.AppLifecycleListener
import com.intellij.idea.IdeaLogger
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileAttributes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.impl.VirtualFileManagerImpl
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.util.io.URLUtil
import com.jetbrains.rd.util.collections.SynchronizedMap
import java.io.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock


class StartupListener: AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        MyLogger.setup()
    }
}

class MyLogger(category: String): DefaultLogger(category) {

    override fun error(message: String?) {
        if (message?.contains(">1 file system registered for protocol") == true) {
            return
        }
        super.error(message)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        if (message?.contains(">1 file system registered for protocol") == true) {
            return
        }
        super.error(message, t, *details)
    }

    companion object {
        fun setup() {
            //IdeaLogger.setFactory { category -> MyLogger(category) }
            //Logger.setFactory { category -> MyLogger(category) }
        }
//        val logger = setup()
    }
}

val myResourceLock = ReentrantLock()

class WslSymlinksProvider(distro: String) {
    val LOGGER = Logger.getInstance(WslSymlinksProvider::class.java)

    private var process: Process
    private var processReader: BufferedReader
    private var processWriter: BufferedWriter
    private val queue: LinkedBlockingQueue<AsyncValue> = LinkedBlockingQueue()
    private val mapped: SynchronizedMap<String, AsyncValue> = SynchronizedMap()

    class AsyncValue {
        public val id = this.hashCode().toString()
        public var request: String? = null
        internal var value: String? = null
        internal val condition = myResourceLock.newCondition()
        fun getValue(): String? {
            var elapsed = 0
            while (value == null && elapsed < 500) {
                if (myResourceLock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    condition.await(10, TimeUnit.MILLISECONDS)
                    myResourceLock.unlock()
                }
                elapsed += 10
            }
//            if (this.value == null) {
//                throw Error("failed to obtain file info for $request")
//            }
            return this.value
        }
    }

    init {
        val bash = {}.javaClass.getResource("/files.sh")?.readText()!!
        val location = "\\\\wsl.localhost\\$distro\\var\\tmp\\intellij-idea-wsl-symlinks.sh"
        File(location).writeText(bash)
        val builder = ProcessBuilder("wsl.exe", "-d", distro,  "-e", "bash", "//var/tmp/intellij-idea-wsl-symlinks.sh")
        val process = builder.start()
        this.process = process

        val stdin: OutputStream = process.outputStream // <- Eh?
        val stdout: InputStream = process.inputStream

        val reader = BufferedReader(InputStreamReader(stdout))
        val writer = BufferedWriter(OutputStreamWriter(stdin))
        this.processReader = reader;
        this.processWriter = writer;

        process.onExit().whenComplete { t, u ->
            LOGGER.error("process did exit", u)
            this.process = builder.start()
            this.processReader = BufferedReader(InputStreamReader(process.inputStream))
            this.processWriter = BufferedWriter(OutputStreamWriter(process.outputStream));
        }

        thread {
            while (true) {
                try {
                    val line = this.processReader.readLine()
                    val (id, answer) = line.split(";")
                    val a = mapped[id]!!
                    a.value = answer
                    myResourceLock.withLock {
                        a.condition.signalAll()
                    }
                    mapped.remove(id)
                } catch (e: Exception) {
                    LOGGER.error("failed to write", e)
                }
            }
        }

        thread {
            while (true) {
                try {
                    val a = this.queue.take()
                    mapped[a.id] = a
                    this.processWriter.write(a.request!!)
                    this.processWriter.flush()
                } catch (e: Exception) {
                    LOGGER.error("failed to write", e)
                }
            }
        }
    }

    fun getWSLCanonicalPath(file: VirtualFile): String? {
        if (!file.isFromWSL()) {
            return null
        }

        if (file.cachedWSLCanonicalPath == null) {
            try {
                val wslPath = file.getWSLPath()
                val a = AsyncValue()
                a.request = "${a.id};read-symlink;${wslPath}\n"
                this.queue.add(a)
                while (a.getValue() == null) {
                    if (!queue.contains(a)) {
                        this.queue.add(a)
                    }
                }
                val link = a.getValue()
                file.cachedWSLCanonicalPath = file.path.split("/").subList(0, 4).joinToString("/") + link
            } catch (e: IOException) {
                LOGGER.error("failed to getWSLCanonicalPath", e)
            }
        }

        return file.cachedWSLCanonicalPath
    }

    fun isWslSymlink(file: VirtualFile): Boolean {
        if (file.isSymlink != null) {
            return file.isSymlink!!
        }
        if (file.isFromWSL() && file.parent != null) {
            try {
                val path: String = file.path.replace("^//wsl\\$/[^/]+".toRegex(), "").replace("""^//wsl.localhost/[^/]+""".toRegex(), "")
                val a = AsyncValue()
                a.request = "${a.id};is-symlink;${path}\n"
                while (a.getValue() == null) {
                    if (!queue.contains(a)) {
                        this.queue.add(a)
                    }
                }
                val isSymLink = a.getValue()
                file.isSymlink = isSymLink.equals("true")
                return isSymLink.equals("true")
            } catch (e: Exception) {
                LOGGER.error("failed isWslSymlink", e)
                return false
            }
        }
        return false
    }
}

class WslVirtualFileSystem: LocalFileSystemImpl() {
    private var wslSymlinksProviders: MutableMap<String, WslSymlinksProvider> = HashMap()
    private var reentry: VirtualFile? = null
    val lock = ReentrantLock()

    override fun getProtocol(): String {
        return "file"
    }

    fun getWslSymlinksProviders(file: VirtualFile): WslSymlinksProvider {
        val distro = file.getWSLDistribution()!!
        if (!this.wslSymlinksProviders.containsKey(distro)) {
            this.wslSymlinksProviders[distro] = WslSymlinksProvider(distro)
        }
        return this.wslSymlinksProviders[distro]!!
    }

    fun getRealVirtualFile(file: VirtualFile): VirtualFile {
        lock.withLock {
            if (reentry == file) {
                throw Error("error")
            }
            reentry = file
            val symlkinkWsl = file.parents.find { it.isFromWSL() && this.getWslSymlinksProviders(file).isWslSymlink(it) }
            val relative = symlkinkWsl?.path?.let { file.path.replace(it, "") }
            val resolved = symlkinkWsl?.let { virtualFile -> this.resolveSymLink(virtualFile)?.let { this.findFileByPath(it) } }
            val r = relative?.let { resolved?.findFileByRelativePath(it) } ?: file
            reentry = null
            return r
        }
    }

    override fun contentsToByteArray(vfile: VirtualFile): ByteArray {
        val file = this.getRealVirtualFile(vfile)
        return super.contentsToByteArray(file)
    }

    override fun getOutputStream(vfile: VirtualFile, requestor: Any?, modStamp: Long, timeStamp: Long): OutputStream {
        val file = this.getRealVirtualFile(vfile)
        return super.getOutputStream(file, requestor, modStamp, timeStamp)
    }

    override fun getAttributes(vfile: VirtualFile): FileAttributes? {
        val file = getRealVirtualFile(vfile)
        var attributes = super.getAttributes(file)
        if (attributes != null && attributes.type == null && this.getWslSymlinksProviders(file).isWslSymlink(file)) {
            val resolved = this.resolveSymLink(file)?.let { this.findFileByPath(it) }
            if (resolved != null) {
                val resolvedAttrs = super.getAttributes(resolved)
                attributes = FileAttributes(resolvedAttrs?.isDirectory ?: false, false, true, attributes.isHidden, attributes.length, attributes.lastModified, attributes.isWritable, FileAttributes.CaseSensitivity.SENSITIVE)
            }

        }
        return attributes
    }

    override fun resolveSymLink(file: VirtualFile): String? {
        if (file.isFromWSL()) {
            return this.getWslSymlinksProviders(file).getWSLCanonicalPath(file);
        }
        return super.resolveSymLink(file)
    }
    companion object {
        const val PROTOCOL = "\\wsl.localhost"

        fun getInstance() = service<VirtualFileManager>().getFileSystem(PROTOCOL) as WslVirtualFileSystem
    }

    override fun list(vfile: VirtualFile): Array<String> {
        val file = getRealVirtualFile(vfile)
        if (file.isFromWSL() && this.getWslSymlinksProviders(file).isWslSymlink(file)) {
            val f = this.resolveSymLink(file)?.let { this.findFileByPath(it) }
            return f?.let { super.list(it) } ?: emptyArray()
        }
        return super.list(file)
    }
}

val weakMap = WeakHashMap<VirtualFile, String?>()
val weakMapSymlink = WeakHashMap<VirtualFile, Boolean?>()

private var VirtualFile.cachedWSLCanonicalPath: String?
    get() {
        return weakMap[this]
    }
    set(value) {
        weakMap[this] = value
    }

private var VirtualFile.isSymlink: Boolean?
    get() {
        return weakMapSymlink[this]
    }
    set(value) {
        weakMapSymlink[this] = value
    }


private fun VirtualFile.isFromWSL(): Boolean {
    return this.path.startsWith("//wsl$/") || path.startsWith("//wsl.localhost");
}

private fun VirtualFile.getWSLDistribution(): String? {
    if (!isFromWSL()) {
        return null;
    }

    return path.replace("""^//wsl\$/""".toRegex(), "")
            .replace("""^//wsl.localhost/""".toRegex(), "").split("/")[0]

}

private fun VirtualFile.getWSLPath(): String {
    return path.replace("""^//wsl\$/[^/]+""".toRegex(), "")
            .replace("""^//wsl.localhost/[^/]+""".toRegex(), "")
}

public val VirtualFileUrl.getVirtualFile: VirtualFile?
    get() {
        if (url.startsWith(WslVirtualFileSystem.PROTOCOL)) {
            val protocolSepIndex = url.indexOf(URLUtil.SCHEME_SEPARATOR)
            val fileSystem: VirtualFileSystem? = if (protocolSepIndex < 0) null else WslVirtualFileSystem.getInstance()
            if (fileSystem == null) return null
            val path = url.substring(protocolSepIndex + URLUtil.SCHEME_SEPARATOR.length)
            return fileSystem.findFileByPath(path)
        }
        return null
    }


val VirtualFile.parents: Iterable<VirtualFile>
    get() = object : Iterable<VirtualFile> {
        override fun iterator(): Iterator<VirtualFile> {
            var file = this@parents

            return object : Iterator<VirtualFile> {
                override fun hasNext() = file.parent != null
                override fun next(): VirtualFile {
                    file = file.parent
                    return file
                }
            }
        }
    }