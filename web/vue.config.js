module.exports = { parallel: false, devServer: { port: 8081, proxy: { '/api': { target: 'http://localhost:8082', changeOrigin: true } } }, productionSourceMap: false }
