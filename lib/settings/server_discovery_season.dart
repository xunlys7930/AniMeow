const Set<int> serverDiscoverySeasonMonths = {1, 4, 7, 10};
const int serverDiscoverySeasonStartDay = 25;

class ServerDiscoverySeason {
  const ServerDiscoverySeason({required this.year, required this.month});

  final int year;
  final int month;
}

bool isAirDateInServerDiscoverySeason(
  String? airDate, {
  required int seasonMonth,
  int? seasonYear,
}) {
  if (!serverDiscoverySeasonMonths.contains(seasonMonth)) return false;

  final date = parseServerDiscoveryAirDate(airDate);
  if (date == null) return false;

  if (seasonYear == null) {
    return serverDiscoverySeasonForDate(date).month == seasonMonth;
  }

  final start = serverDiscoverySeasonStart(seasonYear, seasonMonth);
  final end = serverDiscoveryNextSeasonStart(seasonYear, seasonMonth);
  return !date.isBefore(start) && date.isBefore(end);
}

DateTime? parseServerDiscoveryAirDate(String? airDate) {
  final text = airDate?.trim();
  if (text == null || text.isEmpty) return null;

  final match = RegExp(r'^(\d{4})-(\d{1,2})(?:-(\d{1,2}))?').firstMatch(text);
  if (match == null || match.group(3) == null) return null;

  final year = int.tryParse(match.group(1)!);
  final month = int.tryParse(match.group(2)!);
  final day = int.tryParse(match.group(3)!);
  if (year == null || month == null || day == null) return null;

  final date = DateTime(year, month, day);
  if (date.year != year || date.month != month || date.day != day) {
    return null;
  }

  return date;
}

ServerDiscoverySeason serverDiscoverySeasonForDate(DateTime date) {
  if (date.day >= serverDiscoverySeasonStartDay) {
    switch (date.month) {
      case 3:
        return ServerDiscoverySeason(year: date.year, month: 4);
      case 6:
        return ServerDiscoverySeason(year: date.year, month: 7);
      case 9:
        return ServerDiscoverySeason(year: date.year, month: 10);
      case 12:
        return ServerDiscoverySeason(year: date.year + 1, month: 1);
    }
  }

  if (date.month <= 3) {
    return ServerDiscoverySeason(year: date.year, month: 1);
  }
  if (date.month <= 6) {
    return ServerDiscoverySeason(year: date.year, month: 4);
  }
  if (date.month <= 9) {
    return ServerDiscoverySeason(year: date.year, month: 7);
  }
  return ServerDiscoverySeason(year: date.year, month: 10);
}

DateTime serverDiscoverySeasonStart(int seasonYear, int seasonMonth) {
  switch (seasonMonth) {
    case 1:
      return DateTime(seasonYear - 1, 12, serverDiscoverySeasonStartDay);
    case 4:
    case 7:
    case 10:
      return DateTime(
        seasonYear,
        seasonMonth - 1,
        serverDiscoverySeasonStartDay,
      );
  }

  throw ArgumentError.value(
    seasonMonth,
    'seasonMonth',
    'Expected one of 1, 4, 7, 10.',
  );
}

DateTime serverDiscoveryNextSeasonStart(int seasonYear, int seasonMonth) {
  switch (seasonMonth) {
    case 1:
      return serverDiscoverySeasonStart(seasonYear, 4);
    case 4:
      return serverDiscoverySeasonStart(seasonYear, 7);
    case 7:
      return serverDiscoverySeasonStart(seasonYear, 10);
    case 10:
      return serverDiscoverySeasonStart(seasonYear + 1, 1);
  }

  throw ArgumentError.value(
    seasonMonth,
    'seasonMonth',
    'Expected one of 1, 4, 7, 10.',
  );
}
