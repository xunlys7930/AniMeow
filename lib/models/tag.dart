class Tag {
  final int? id;
  final String name;
  final int? color;
  final int? count;

  const Tag({this.id, required this.name, this.color, this.count});

  factory Tag.fromMap(Map<String, dynamic> map) {
    return Tag(
      id: _asInt(map['id']),
      name: map['name'] as String? ?? '',
      color: _asInt(map['color']),
      count: _asInt(map['count']),
    );
  }

  Map<String, dynamic> toMap({bool includeId = true}) {
    return {
      if (includeId && id != null) 'id': id,
      'name': name,
      if (color != null) 'color': color,
      if (count != null) 'count': count,
    };
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}
