class BangumiCharacter {
  final int id;
  final String name;
  final String? nameCn;
  final String? imageUrl;
  final String? summary;
  final String? gender;
  final int? birthYear;
  final int? birthMonth;
  final int? birthDay;
  final String? bloodType;
  final String infoboxJson;

  const BangumiCharacter({
    required this.id,
    required this.name,
    this.nameCn,
    this.imageUrl,
    this.summary,
    this.gender,
    this.birthYear,
    this.birthMonth,
    this.birthDay,
    this.bloodType,
    this.infoboxJson = '[]',
  });

  String get displayName {
    final cn = nameCn?.trim();
    if (cn != null && cn.isNotEmpty) return cn;
    return name;
  }

  String get originalName => name;

  Map<String, dynamic> toDbMap() {
    return {
      'bgm_id': id,
      'name': name,
      'name_cn': nameCn,
      'image_url': imageUrl,
      'summary': summary,
      'gender': gender,
      'birth_year': birthYear,
      'birth_mon': birthMonth,
      'birth_day': birthDay,
      'blood_type': bloodType,
      'infobox_json': infoboxJson,
      'updated_at': DateTime.now().toIso8601String(),
    };
  }
}
