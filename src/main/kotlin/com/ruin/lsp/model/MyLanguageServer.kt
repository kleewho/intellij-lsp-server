package com.ruin.lsp.model

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.ruin.lsp.commands.DocumentCommand
import com.ruin.lsp.commands.ExecutionContext
import com.ruin.lsp.commands.ProjectCommand
import com.ruin.lsp.commands.document.diagnostics.DiagnosticsThread
import com.ruin.lsp.commands.document.find.FindImplementationCommand
import com.ruin.lsp.commands.project.dialog.OpenProjectStructureCommand
import com.ruin.lsp.commands.project.dialog.ToggleFrameVisibilityCommand
import com.ruin.lsp.util.*
import com.ruin.lsp.values.DocumentUri
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class MyLanguageServer : LanguageServer, MyLanguageServerExtensions, LanguageClientAware {
    private val LOG = Logger.getInstance(MyLanguageServer::class.java)
    var context = Context()

    val workspace: WorkspaceManager by lazy { ServiceManager.getService<WorkspaceManager>(WorkspaceManager::class.java)!! }

    var myTextDocumentService = MyTextDocumentService(this)
    var myWorkspaceService = MyWorkspaceService(this)

    var client: MyLanguageClient? = null
    var diagnosticsFutures: HashMap<DocumentUri, Future<*>> = HashMap()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return CompletableFuture.supplyAsync {
            context.clientCapabilities = params.capabilities

            LOG.info("LSP was initialized.")

            InitializeResult(defaultServerCapabilities())
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        workspace.onShutdown()

        return CompletableFuture.completedFuture(Unit)
    }

    override fun exit() {

    }

    override fun connect(client: LanguageClient?) {
        this.client = client as MyLanguageClient
    }

    fun computeDiagnostics(uri: DocumentUri) {
        if (client == null) {
            return
        }

        val (doc, file) = invokeAndWaitIfNeeded( Computable<Pair<Document, PsiFile>?> {
            val (_, file) = resolvePsiFromUri(uri) ?: return@Computable null
            val doc = getDocument(file) ?: return@Computable null
            Pair(doc, file)
        }) ?: return

        diagnosticsFutures[uri]?.cancel(true)

        diagnosticsFutures[uri] = ApplicationManager.getApplication()
            .executeOnPooledThread(DiagnosticsThread(file, doc, client!!))
    }

    override fun getTextDocumentService() = myTextDocumentService

    override fun getWorkspaceService() = myWorkspaceService


    // LSP protocol extensions for IDEA-specific features

    override fun implementations(params: TextDocumentPositionParams): CompletableFuture<MutableList<Location>> =
        asInvokeAndWaitFuture(params.textDocument.uri, FindImplementationCommand(params.position), client)

    override fun openProjectStructure(params: TextDocumentPositionParams): CompletableFuture<Boolean> =
        asInvokeAndWaitFuture(params.textDocument.uri, OpenProjectStructureCommand())

    override fun toggleFrameVisibility(params: TextDocumentPositionParams): CompletableFuture<Boolean> =
        asInvokeAndWaitFuture(params.textDocument.uri, ToggleFrameVisibilityCommand())
}


fun <T: Any> asInvokeAndWaitFuture(
    uri: DocumentUri,
    command: ProjectCommand<T>): CompletableFuture<T> =
    CompletableFuture.supplyAsync {
        invokeAndWaitIfNeeded(Computable<T> {
            val project = ensureProjectFromUri(uri).first
            command.execute(project)
        })
    }

fun <T: Any> asInvokeAndWaitFuture(
    uri: DocumentUri,
    command: DocumentCommand<T>,
    client: LanguageClient? = null,
    server: LanguageServer? = null): CompletableFuture<T> =
     CompletableFuture.supplyAsync {
        executeAndGetResult(uri, command, client, server)
    }

fun <T: Any> asCancellableInvokeAndWaitFuture(
    uri: DocumentUri,
    command: DocumentCommand<T>,
    client: LanguageClient? = null,
    server: LanguageServer? = null): CompletableFuture<T> =
    CompletableFutures.computeAsync { cancelToken ->
        executeAndGetResult(uri, command, client, server, cancelToken)
    }

private val LOG = Logger.getInstance(MyLanguageServer::class.java)

private fun <T : Any> executeAndGetResult(
    uri: DocumentUri,
    command: DocumentCommand<T>,
    client: LanguageClient? = null,
    server: LanguageServer? = null,
    cancelToken: CancelChecker? = null): T {
    val (project, file) = ensurePsiFromUri(uri)
    return invokeAndWaitIfNeeded(Computable<T> {
        val profiler = if (client != null) startProfiler(client) else DUMMY
        val context = ExecutionContext(project, file, client, server, profiler, cancelToken)
        profiler.finish("Done")
        val result = command.execute(context)
        command.dispose()
        result
    })
}


fun <T: Any> invokeCommandAndWait(command: com.ruin.lsp.commands.DocumentCommand<T>,
                                  uri: DocumentUri,
                                  client: LanguageClient? = null): T {
    val (project, file) = ensurePsiFromUri(uri)
    val context = ExecutionContext(project, file, client)

    val result = invokeAndWaitIfNeeded(Computable {
        command.execute(context)
    })

    command.dispose()
    return result
}

fun <T: Any> invokeCommandAndWait(command: com.ruin.lsp.commands.ProjectCommand<T>,
                                  project: Project): T {
    val result = invokeAndWaitIfNeeded(Computable {
        command.execute(project)
    })

    command.dispose()
    return result
}

fun defaultServerCapabilities() =
     ServerCapabilities().apply {
        textDocumentSync = null
        hoverProvider = true
        completionProvider = CompletionOptions(false, listOf(".", "@", "#"))
        signatureHelpProvider = null
        definitionProvider = true
        referencesProvider = true
        documentHighlightProvider = true
        documentSymbolProvider = true
        workspaceSymbolProvider = false
        codeActionProvider = false
        codeLensProvider = null
        documentFormattingProvider = false
        documentRangeFormattingProvider = false
        documentOnTypeFormattingProvider = null
        renameProvider = false
        documentLinkProvider = null
        executeCommandProvider = null
        experimental = null
    }
