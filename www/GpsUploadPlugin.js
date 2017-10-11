
	
	var exec = require('cordova/exec');
	var GpsUploadPlugin = function(){
	};

	GpsUploadPlugin.prototype.error_callback = function(msg) {
		console.log("Javascript Callback Error: " + msg);
	}

	GpsUploadPlugin.prototype.call_native = function(name, args, callback) {
		ret = cordova.exec(callback, this.error_callback, 'GpsUploadPlugin', name, args);
		return ret;
	}

	GpsUploadPlugin.prototype.start = function(carNo, carColor,callback,host,port,uptime,fatUrl) {
		uptime = uptime || 10000;
		this.call_native("beginLoc", [carNo, carColor, host, port, uptime,fatUrl], callback);
	}

	GpsUploadPlugin.prototype.stop = function(args, callback) {
		args = args || [];
		this.call_native("stop", [], callback);
	}

	GpsUploadPlugin.prototype.checkWifi = function(callback) {
		this.call_native("checkWifi", [], callback);
	}

	GpsUploadPlugin.prototype.checkGps = function(args, callback) {
		args = args || [];
		this.call_native("checkGps", [], callback);
	}

	window.plugins = window.plugins || {};
	window.plugins.GpsUploadPlugin = window.plugins.GpsUploadPlugin || new GpsUploadPlugin();

	module.exports = new GpsUploadPlugin();