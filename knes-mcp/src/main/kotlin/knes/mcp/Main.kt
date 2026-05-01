package knes.mcp

fun main(args: Array<String>) {
    val server = if (args.contains("--remote")) createRemoteMcpServer() else createMcpServer()
    runMcpServer(server)
}
