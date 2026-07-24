part of 'series_detail_page.dart';

extension _SeriesDetailPageStateUI on _SeriesDetailPageState {
  Widget _buildSelectionActionBar() {
    final bool hasSelection = _selectedIds.isNotEmpty;
    final Color disabledColor = Colors.grey.shade400;

    return Container(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).padding.bottom + 8,
        top: 8,
        left: 8,
        right: 8,
      ),
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 10,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _buildActionButton(
            icon: Icons.sync,
            label: "同步",
            onPressed: hasSelection ? _batchAutoMatchInfo : null,
            color: hasSelection ? Colors.teal : disabledColor,
          ),
          _buildActionButton(
            icon: Icons.edit_attributes,
            label: "状态",
            onPressed: hasSelection ? _showBatchStatusDialog : null,
            color: hasSelection ? Colors.blue : disabledColor,
          ),
          _buildActionButton(
            icon: Icons.label_outline,
            label: "标签",
            onPressed: hasSelection ? _showBatchTagMenu : null,
            color: hasSelection ? Colors.orange : disabledColor,
          ),
          _buildActionButton(
            icon: Icons.link_off,
            label: "移出系列",
            onPressed: hasSelection ? _batchRemoveFromSeries : null,
            color: hasSelection ? Colors.deepOrange : disabledColor,
          ),
          _buildActionButton(
            icon: Icons.delete_outline,
            label: "删除",
            onPressed: hasSelection ? _batchDelete : null,
            color: hasSelection ? Colors.red : disabledColor,
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    required Color color,
  }) {
    return InkWell(
      onTap: onPressed,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: 70,
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: color, size: 24),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                color: color,
                fontSize: 11,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCoverImage(String? url, {double? width, double? height}) {
    if (url == null || url.isEmpty) {
      return Container(
        width: width,
        height: height,
        color: Colors.grey[200],
        child: const Icon(Icons.collections_bookmark, color: Colors.grey),
      );
    }

    if (url.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: url,
        width: width,
        height: height,
        fit: BoxFit.cover,
        placeholder: (context, url) => Container(color: Colors.white10),
        errorWidget: (_, __, ___) => Container(color: Colors.grey[200]),
      );
    }

    return FutureBuilder<Directory>(
      future: getApplicationDocumentsDirectory(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return Container(width: width, height: height, color: Colors.white10);
        }
        final file = File(
          path.isAbsolute(url) ? url : path.join(snapshot.data!.path, url),
        );
        return Image.file(
          file,
          width: width,
          height: height,
          fit: BoxFit.cover,
        );
      },
    );
  }

  Widget _buildAnimeCard(Map<String, dynamic> anime) {
    final id = anime['id'];
    final isSelected = _selectedIds.contains(id);
    final coverBorderRadius = SettingsManager().coverBorderRadiusNotifier.value;

    return GestureDetector(
      onTap: () async {
        if (_isSelectionMode) {
          _toggleItemSelection(id);
        } else {
          await Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => AnimeDetailPage(existingAnime: anime),
            ),
          );
          _loadData();
        }
      },
      onLongPress: () {
        if (!_isSelectionMode) {
          _toggleSelectionMode(true);
          _toggleItemSelection(id);
        }
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(coverBorderRadius),
          border: _isSelectionMode && isSelected
              ? Border.all(color: Theme.of(context).primaryColor, width: 3)
              : null,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.1),
              blurRadius: 4,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(coverBorderRadius),
          ),
          elevation: 0,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    _buildCoverImage(anime['cover_url']),
                    Positioned(
                      top: 6,
                      left: 6,
                      child: _StatusBadge(
                        status: anime['status'],
                        color:
                            widget.statusColors[anime['status']] ?? Colors.grey,
                      ),
                    ),
                    if (_isSelectionMode && isSelected)
                      Container(
                        color: Theme.of(
                          context,
                        ).primaryColor.withValues(alpha: 0.1),
                        child: Center(
                          child: Icon(
                            Icons.check_circle,
                            color: Theme.of(context).primaryColor,
                            size: 32,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(8.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      anime['title'],
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 13,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      "TV ${anime['total_episodes']}集",
                      style: TextStyle(fontSize: 11, color: Colors.grey[600]),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
