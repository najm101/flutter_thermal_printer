class PrinterStatus {
  const PrinterStatus({
    required this.isOnline,
    required this.hasPaper,
    required this.isPaperNearEnd,
    required this.isCoverOpen,
    required this.hasCutterError,
    required this.hasUnrecoverableError,
    required this.isWaitingForRecovery,
    required this.drawerKickOutPin,
    this.rawByte1,
    this.rawByte2,
    this.rawByte3,
    this.rawByte4,
  });

  factory PrinterStatus.fromMap(Map<dynamic, dynamic> map) => PrinterStatus(
        isOnline: map['isOnline'] as bool? ?? false,
        hasPaper: map['hasPaper'] as bool? ?? false,
        isPaperNearEnd: map['isPaperNearEnd'] as bool? ?? false,
        isCoverOpen: map['isCoverOpen'] as bool? ?? false,
        hasCutterError: map['hasCutterError'] as bool? ?? false,
        hasUnrecoverableError: map['hasUnrecoverableError'] as bool? ?? false,
        isWaitingForRecovery: map['isWaitingForRecovery'] as bool? ?? false,
        drawerKickOutPin: map['drawerKickOutPin'] as bool? ?? false,
        rawByte1: map['rawByte1'] as int?,
        rawByte2: map['rawByte2'] as int?,
        rawByte3: map['rawByte3'] as int?,
        rawByte4: map['rawByte4'] as int?,
      );

  /// Printer is online and ready
  final bool isOnline;

  /// Roll paper is present
  final bool hasPaper;

  /// Paper is running low (near-end sensor triggered)
  final bool isPaperNearEnd;

  /// Printer cover/hatch is open
  final bool isCoverOpen;

  /// Auto-cutter has an error
  final bool hasCutterError;

  /// Printer has an unrecoverable hardware error
  final bool hasUnrecoverableError;

  /// Printer is waiting for an online recovery condition to clear
  final bool isWaitingForRecovery;

  /// Drawer kick-out connector pin 3 is HIGH
  final bool drawerKickOutPin;

  /// Raw response bytes from each DLE EOT command (-1 = not queried)
  final int? rawByte1;
  final int? rawByte2;
  final int? rawByte3;
  final int? rawByte4;

  /// Human-readable list of active error/warning descriptions
  List<String> get errorDescriptions {
    final errors = <String>[];
    if (!isOnline) {
      errors.add('Printer is offline');
    }
    if (isCoverOpen) {
      errors.add('Cover is open');
    }
    if (!hasPaper) {
      errors.add('Paper empty');
    }
    if (isPaperNearEnd) {
      errors.add('Paper near end');
    }
    if (hasCutterError) {
      errors.add('Auto-cutter error');
    }
    if (hasUnrecoverableError) {
      errors.add('Unrecoverable hardware error');
    }
    if (isWaitingForRecovery) {
      errors.add('Waiting for recovery');
    }
    return errors;
  }

  /// True if any error or warning condition is active
  bool get hasAnyError =>
      !isOnline ||
      isCoverOpen ||
      !hasPaper ||
      hasCutterError ||
      hasUnrecoverableError;

  Map<String, dynamic> toJson() => {
        'isOnline': isOnline,
        'hasPaper': hasPaper,
        'isPaperNearEnd': isPaperNearEnd,
        'isCoverOpen': isCoverOpen,
        'hasCutterError': hasCutterError,
        'hasUnrecoverableError': hasUnrecoverableError,
        'isWaitingForRecovery': isWaitingForRecovery,
        'drawerKickOutPin': drawerKickOutPin,
        'errorDescriptions': errorDescriptions,
        'debugCodes': {
          'dleEot1': rawByte1,
          'dleEot2': rawByte2,
          'dleEot3': rawByte3,
          'dleEot4': rawByte4,
        },
      };

  @override
  String toString() => 'PrinterStatus(${toJson()})';
}
