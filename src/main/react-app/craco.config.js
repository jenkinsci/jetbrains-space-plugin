module.exports = {
    webpack: {
        configure: (webpackConfig) => {
            webpackConfig.output = {
                ...webpackConfig.output,
                library: 'ReactApp',
                libraryTarget: 'umd',
                globalObject: 'this',
            };
            return webpackConfig;
        },
    },
};