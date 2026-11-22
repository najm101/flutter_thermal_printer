// ignore_for_file: constant_identifier_names

import 'package:universal_ble/universal_ble.dart';

/// Optimized printer model with better data validation and serialization
class Printer extends BleDevice {
  Printer({
    this.address,
    this.name,
    this.connectionType,
    this.isConnected,
    this.vendorId,
    this.productId,
    this.isPrinterClass,
    this.isInUseBySystem,
  }) : super(deviceId: address ?? '', name: name ?? '');

  /// Create Printer from JSON with validation
  factory Printer.fromJson(Map<String, dynamic> json) {
    try {
      return Printer(
        address: json['address'] as String?,
        name: json['name'] as String?,
        connectionType:
            _getConnectionTypeFromString(json['connectionType'] as String?),
        isConnected: json['isConnected'] as bool?,
        vendorId: json['vendorId']?.toString(),
        productId: json['productId']?.toString(),
        isPrinterClass: json['isPrinterClass'] as bool?,
        isInUseBySystem: json['isInUseBySystem'] as bool?,
      );
    } catch (e) {
      throw FormatException('Invalid Printer JSON format: $e');
    }
  }

  @override
  // ignore: overridden_fields
  final String? name;
  final String? address;
  final ConnectionType? connectionType;
  final bool? isConnected;
  final String? vendorId;
  final String? productId;

  /// USB only: whether the device declares the USB printer class (7). Null when
  /// unknown (non-USB, or an older native side). Vendor-class printers report
  /// false yet print normally — use this only as a preference, never a gate.
  final bool? isPrinterClass;

  /// USB only: an Android driver owns this device and the app did not allow a
  /// takeover (e.g. a USB network adapter). It is left alone — never opened,
  /// polled or auto-selected — until re-plugged or explicitly connected.
  final bool? isInUseBySystem;

  /// Convert to JSON with proper formatting
  Map<String, dynamic> toJson() {
    final data = <String, dynamic>{};

    data['address'] = address;
    data['name'] = name;
    data['connectionType'] = connectionType?.name;
    data['isConnected'] = isConnected;
    data['vendorId'] = vendorId;
    data['productId'] = productId;
    data['isPrinterClass'] = isPrinterClass;
    data['isInUseBySystem'] = isInUseBySystem;

    return data;
  }

  /// Get human-readable connection type string
  String get connectionTypeString {
    switch (connectionType) {
      case ConnectionType.BLE:
        return 'BLE';
      case ConnectionType.USB:
        return 'USB';
      case ConnectionType.NETWORK:
        return 'NETWORK';
      default:
        return 'UNKNOWN';
    }
  }

  /// Create a copy with updated fields
  Printer copyWith({
    String? address,
    String? name,
    ConnectionType? connectionType,
    bool? isConnected,
    String? vendorId,
    String? productId,
    bool? isPrinterClass,
    bool? isInUseBySystem,
  }) =>
      Printer(
        address: address ?? this.address,
        name: name ?? this.name,
        connectionType: connectionType ?? this.connectionType,
        isConnected: isConnected ?? this.isConnected,
        vendorId: vendorId ?? this.vendorId,
        productId: productId ?? this.productId,
        isPrinterClass: isPrinterClass ?? this.isPrinterClass,
        isInUseBySystem: isInUseBySystem ?? this.isInUseBySystem,
      );

  /// Generate unique identifier for the printer
  String get uniqueId {
    final buffer = StringBuffer();
    if (vendorId != null) {
      buffer.write(vendorId);
    }
    buffer.write('_');
    if (address != null) {
      buffer.write(address);
    }
    return buffer.toString();
  }

  /// Check if printer has valid connection data
  bool get hasValidConnectionData {
    switch (connectionType) {
      case ConnectionType.USB:
        return vendorId != null && productId != null;
      case ConnectionType.BLE:
        return address != null;
      case ConnectionType.NETWORK:
        return address != null;
      default:
        return false;
    }
  }

  @override
  String toString() =>
      'Printer(name: $name, connectionType: ${connectionType?.name}, '
      'address: $address, isConnected: $isConnected)';

  /// Convert string to ConnectionType enum
  static ConnectionType? _getConnectionTypeFromString(String? type) {
    if (type == null) {
      return null;
    }

    switch (type.toUpperCase()) {
      case 'BLE':
        return ConnectionType.BLE;
      case 'USB':
        return ConnectionType.USB;
      case 'NETWORK':
        return ConnectionType.NETWORK;
      default:
        return null;
    }
  }
}

/// Enhanced connection type enum
enum ConnectionType {
  BLE,
  USB,
  NETWORK,
}
