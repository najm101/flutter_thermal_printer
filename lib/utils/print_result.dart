/// Error codes for USB print failures on Android.
/// BLE and Windows paths use [PrintErrorCode.platformException] or
/// [PrintErrorCode.unknown] when they fail.
enum PrintErrorCode {
  notConnected,
  deviceNotFound,
  permissionDenied,
  writeFailed,
  writeIncomplete,
  writeTimeout,
  printerOffline,
  coverOpen,
  noPaper,
  platformException,
  unknown,
}

/// Result returned by printData and related print methods.
class PrintResult {
  const PrintResult._({
    required this.success,
    required this.bytesWritten,
    this.errorCode,
    this.message,
  });

  factory PrintResult.ok(int bytesWritten) => PrintResult._(
        success: true,
        bytesWritten: bytesWritten,
      );

  factory PrintResult.failure(PrintErrorCode code, String msg) =>
      PrintResult._(
        success: false,
        bytesWritten: 0,
        errorCode: code,
        message: msg,
      );

  factory PrintResult.fromMap(Map<dynamic, dynamic> map) {
    final ok = map['success'] as bool? ?? false;
    if (ok) {
      return PrintResult.ok((map['bytesWritten'] as int?) ?? 0);
    }
    final codeStr = map['errorCode'] as String? ?? 'unknown';
    return PrintResult.failure(
      _codeFromString(codeStr),
      map['message'] as String? ?? codeStr,
    );
  }

  final bool success;
  final int bytesWritten;
  final PrintErrorCode? errorCode;
  final String? message;

  static PrintErrorCode _codeFromString(String s) {
    switch (s) {
      case 'notConnected':
        return PrintErrorCode.notConnected;
      case 'deviceNotFound':
        return PrintErrorCode.deviceNotFound;
      case 'permissionDenied':
        return PrintErrorCode.permissionDenied;
      case 'writeFailed':
        return PrintErrorCode.writeFailed;
      case 'writeIncomplete':
        return PrintErrorCode.writeIncomplete;
      case 'writeTimeout':
        return PrintErrorCode.writeTimeout;
      case 'printerOffline':
        return PrintErrorCode.printerOffline;
      case 'coverOpen':
        return PrintErrorCode.coverOpen;
      case 'noPaper':
        return PrintErrorCode.noPaper;
      case 'platformException':
        return PrintErrorCode.platformException;
      default:
        return PrintErrorCode.unknown;
    }
  }

  @override
  String toString() => success
      ? 'PrintResult.ok(bytesWritten: $bytesWritten)'
      : 'PrintResult.failure($errorCode: $message)';

}
