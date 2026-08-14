module.exports = { parallel: false, devServer: { port: 8081, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }, productionSourceMap: false }
