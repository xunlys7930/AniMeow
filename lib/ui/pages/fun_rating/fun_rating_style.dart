import 'package:flutter/material.dart';

const Map<String, Color> funRatingTierColors = {
  'S': Color(0xFFFFB300),
  'A': Color(0xFF7E57C2),
  'B': Color(0xFF42A5F5),
  'C': Color(0xFF26A69A),
  'D': Color(0xFF78909C),
};

Color funRatingTierColor(String code) =>
    funRatingTierColors[code] ?? const Color(0xFF78909C);
