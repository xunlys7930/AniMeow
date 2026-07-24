part of 'add_anime_page.dart';

extension _AddAnimePageStateUI on _AddAnimePageState {
  Widget _buildCoverImage(String url) {
    if (url.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: url,
        height: 200,
        fit: BoxFit.cover,
        placeholder: (context, url) => Container(
          height: 200,
          color: Colors.grey[200],
          child: const Center(child: CircularProgressIndicator()),
        ),
        errorWidget: (context, url, error) => _buildErrorPlaceholder(),
      );
    }

    // 本地图片处理 (需异步获取路径)
    return FutureBuilder<Directory>(
      future: getApplicationDocumentsDirectory(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return Container(
            height: 200,
            color: Colors.grey[100],
            child: const Center(
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          );
        }

        File imageFile;
        if (path.isAbsolute(url)) {
          // 兼容旧数据（绝对路径）
          imageFile = File(url);
        } else {
          // 新数据（相对路径），拼接 App 目录
          imageFile = File(path.join(snapshot.data!.path, url));
        }

        return Image.file(
          imageFile,
          height: 200,
          fit: BoxFit.cover,
          errorBuilder: (ctx, err, stack) => _buildErrorPlaceholder(),
        );
      },
    );
  }

  Widget _buildErrorPlaceholder() {
    return Container(
      height: 200,
      width: 150,
      color: Colors.grey[300],
      child: const Icon(Icons.broken_image),
    );
  }

  Widget _buildStudioAutocomplete(Color color) {
    return Autocomplete<String>(
      initialValue: TextEditingValue(text: _studioInput),
      optionsBuilder: (TextEditingValue textEditingValue) {
        if (textEditingValue.text == '') return const Iterable<String>.empty();
        return _knownStudios.where(
          (String option) => option.toLowerCase().contains(
            textEditingValue.text.toLowerCase(),
          ),
        );
      },
      onSelected: (String selection) =>
          _updateEditorState(() => _studioInput = selection),
      fieldViewBuilder:
          (context, textEditingController, focusNode, onFieldSubmitted) {
            // 当 Autocomplete 创建时同步 controller
            if (textEditingController.text != _studioInput) {
              textEditingController.text = _studioInput;
            }
            return TextField(
              controller: textEditingController,
              focusNode: focusNode,
              onChanged: (val) {
                _studioInput = val;
                _notifyFormChanged();
              },
              decoration: _buildInputDecoration(
                _subjectType == 'anime' ? '制作公司' : '出版社/发行/汉化',
                _subjectType == 'anime'
                    ? Icons.business_outlined
                    : Icons.history_edu_outlined,
                color,
              ),
              style: const TextStyle(fontSize: 14),
            );
          },
    );
  }

  Widget _buildTagSection() {
    final colorScheme = Theme.of(context).colorScheme;
    final color = colorScheme.primary;

    // 决定要显示的标签列表
    final tagsToShow = _isTagEditMode
        ? _allTags
        : _allTags.where((t) => _selectedTagIds.contains(t['id'])).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(
                  Icons.label_outline,
                  size: 16,
                  color: color.withValues(alpha: 0.7),
                ),
                const SizedBox(width: 6),
                Text(
                  '标签',
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w900),
                ),
              ],
            ),
            Row(
              children: [
                if (_isTagEditMode)
                  TextButton.icon(
                    onPressed: _showCreateTagDialog,
                    style: TextButton.styleFrom(
                      foregroundColor: color,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(20),
                      ),
                    ),
                    icon: const Icon(Icons.add, size: 16),
                    label: const Text('新建', style: TextStyle(fontSize: 13)),
                  ),
                TextButton.icon(
                  onPressed: () {
                    _updateEditorState(() {
                      _isTagEditMode = !_isTagEditMode;
                    });
                  },
                  style: TextButton.styleFrom(
                    foregroundColor: _isTagEditMode
                        ? colorScheme.onSurfaceVariant
                        : color,
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(20),
                    ),
                  ),
                  icon: Icon(
                    _isTagEditMode ? Icons.check : Icons.edit,
                    size: 16,
                  ),
                  label: Text(
                    _isTagEditMode ? '完成' : '编辑',
                    style: const TextStyle(fontSize: 13),
                  ),
                ),
              ],
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (tagsToShow.isEmpty && !_isTagEditMode)
          Padding(
            padding: const EdgeInsets.only(top: 8.0, bottom: 8.0),
            child: Text(
              "暂无标签，点击编辑添加",
              style: TextStyle(
                color: colorScheme.onSurfaceVariant,
                fontSize: 13,
              ),
            ),
          )
        else
          Wrap(
            spacing: 8.0,
            runSpacing: 10.0,
            children: tagsToShow.map((tag) {
              final isSelected = _selectedTagIds.contains(tag['id']);
              return AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                child: GestureDetector(
                  onLongPress: () => _confirmDeleteTag(tag),
                  child: FilterChip(
                    label: Text(
                      (tag['name'] ?? '').toString(),
                      style: TextStyle(
                        fontSize: 13,
                        color: isSelected
                            ? color
                            : colorScheme.onSurfaceVariant,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.normal,
                      ),
                    ),
                    selected: isSelected,
                    showCheckmark: false,
                    selectedColor: color.withValues(alpha: 0.12),
                    backgroundColor: colorScheme.surfaceContainerHighest,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                      side: BorderSide(
                        color: isSelected
                            ? color.withValues(alpha: 0.5)
                            : colorScheme.outlineVariant,
                        width: 0.8,
                      ),
                    ),
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    visualDensity: VisualDensity.compact,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    onSelected: _isTagEditMode
                        ? (selected) {
                            _updateEditorState(() {
                              if (selected) {
                                _selectedTagIds.add(tag['id']);
                              } else {
                                _selectedTagIds.remove(tag['id']);
                              }
                            });
                          }
                        : null, // 不是编辑模式时禁用点击切换
                  ),
                ),
              );
            }).toList(),
          ),
      ],
    );
  }

  Widget _buildSeriesNavigationSection(Color color) {
    final colorScheme = Theme.of(context).colorScheme;
    return NeumorphicContainer(
      padding: const EdgeInsets.all(20.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.auto_awesome_motion_outlined, size: 20, color: color),
              const SizedBox(width: 8),
              const Text(
                "同系列作品",
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
              ),
              const Spacer(),
              Text(
                "${_seriesSiblings.length} 部相关",
                style: TextStyle(
                  fontSize: 12,
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 110,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              itemCount: _seriesSiblings.length,
              itemBuilder: (context, index) {
                final sibling = _seriesSiblings[index];
                return GestureDetector(
                  onTap: () async {
                    // 跳转到兄弟番剧的详情查看页（不是编辑表单）
                    final result = await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) =>
                            AnimeDetailPage(existingAnime: sibling),
                      ),
                    );
                    if (result == true && mounted) {
                      _loadSeriesSiblings(); // 如果那边修改了，这边也刷新下
                    }
                  },
                  child: Container(
                    width: 200,
                    margin: const EdgeInsets.only(right: 12),
                    decoration: BoxDecoration(
                      color: colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: colorScheme.outlineVariant),
                    ),
                    padding: const EdgeInsets.all(8),
                    child: Row(
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: SizedBox(
                            width: 50,
                            height: 70,
                            child: _buildCoverImage(sibling['cover_url'] ?? ''),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                (sibling['title'] ?? '未命名作品').toString(),
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.bold,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                maxLines: 2,
                              ),
                              const SizedBox(height: 4),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 6,
                                  vertical: 2,
                                ),
                                decoration: BoxDecoration(
                                  color: color.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(4),
                                ),
                                child: Text(
                                  (sibling['status'] ?? '未知').toString(),
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: color,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeaderCard(Color color, {bool showAdvanced = true}) {
    final colorScheme = Theme.of(context).colorScheme;
    return NeumorphicContainer(
      padding: const EdgeInsets.all(20.0),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 封面图带阴影（Hero 标签与列表视图保持一致）
              Hero(
                tag: widget.existingAnime != null
                    ? 'cover_${widget.existingAnime!['id']}'
                    : 'new_anime_cover',
                child: Material(
                  color: Colors.transparent,
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(24),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.1),
                          blurRadius: 12,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: InkWell(
                      onTap: _coverUrl == null
                          ? _pickLocalImage
                          : _previewCover,
                      borderRadius: BorderRadius.circular(24),
                      child: Container(
                        width: 100,
                        height: 140,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(24),
                          border: Border.all(
                            color: colorScheme.outlineVariant,
                            width: 1,
                          ),
                          color: colorScheme.surfaceContainerHighest,
                        ),
                        child: _coverUrl == null
                            ? Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  Icon(
                                    Icons.add_a_photo_outlined,
                                    color: color.withValues(alpha: 0.3),
                                    size: 32,
                                  ),
                                  const SizedBox(height: 6),
                                  Text(
                                    "上传封面",
                                    style: TextStyle(
                                      fontSize: 10,
                                      color: color.withValues(alpha: 0.5),
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                ],
                              )
                            : ClipRRect(
                                borderRadius: BorderRadius.circular(14),
                                child: _buildCoverImage(_coverUrl!),
                              ),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 18),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    TextField(
                      controller: _titleController,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        height: 1.3,
                      ),
                      maxLines: 3,
                      minLines: 1,
                      decoration: InputDecoration(
                        hintText: _subjectType == 'anime' ? '番剧名称' : '小说/漫画名称',
                        hintStyle: TextStyle(
                          color: colorScheme.onSurfaceVariant,
                          fontWeight: FontWeight.normal,
                        ),
                        errorText: _formErrorMessage == '请输入作品标题'
                            ? _formErrorMessage
                            : null,
                        border: InputBorder.none,
                        focusedBorder: UnderlineInputBorder(
                          borderSide: BorderSide(color: color, width: 1.5),
                        ),
                        contentPadding: const EdgeInsets.only(bottom: 4),
                        suffixIcon: SizedBox(
                          width: _subjectType == 'anime' ? 88 : 44,
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.end,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (_subjectType == 'anime')
                                IconButton(
                                  constraints: const BoxConstraints.tightFor(
                                    width: 44,
                                    height: 44,
                                  ),
                                  padding: EdgeInsets.zero,
                                  visualDensity: VisualDensity.compact,
                                  icon: _isImageSearching
                                      ? const SizedBox(
                                          width: 18,
                                          height: 18,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                          ),
                                        )
                                      : const Icon(
                                          Icons.image_search_outlined,
                                          size: 20,
                                        ),
                                  onPressed: _isSearching || _isImageSearching
                                      ? null
                                      : _performImageSearch,
                                  tooltip: '以图搜番',
                                  color: color,
                                ),
                              IconButton(
                                constraints: const BoxConstraints.tightFor(
                                  width: 44,
                                  height: 44,
                                ),
                                padding: EdgeInsets.zero,
                                visualDensity: VisualDensity.compact,
                                icon: _isSearching
                                    ? const SizedBox(
                                        width: 18,
                                        height: 18,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(
                                        Icons.search_rounded,
                                        size: 20,
                                      ),
                                onPressed: _isSearching || _isImageSearching
                                    ? null
                                    : _performSearch,
                                tooltip: '搜索资料',
                                color: color,
                              ),
                            ],
                          ),
                        ),
                        suffixIconConstraints: BoxConstraints(
                          minWidth: _subjectType == 'anime' ? 88 : 44,
                        ),
                      ),
                    ),
                    if (showAdvanced) ...[
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          _buildBroadcastDaySelector(color),
                          const SizedBox(width: 8),
                          _buildBroadcastTimeSelector(color),
                        ],
                      ),
                      const SizedBox(height: 12),
                      InkWell(
                        onTap: _selectSeries,
                        borderRadius: BorderRadius.circular(8),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 6,
                          ),
                          decoration: BoxDecoration(
                            color: color.withValues(alpha: 0.08),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                Icons.collections_bookmark_outlined,
                                size: 14,
                                color: color.withValues(alpha: 0.8),
                              ),
                              const SizedBox(width: 4),
                              Flexible(
                                child: Text(
                                  _seriesName ?? '选择系列',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: _seriesName == null
                                        ? colorScheme.onSurfaceVariant
                                        : color,
                                    fontWeight: _seriesName == null
                                        ? FontWeight.normal
                                        : FontWeight.w600,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                    const SizedBox(height: 16),
                    _buildRatingEditor(color),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildRatingEditor(Color color) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Text(
              '评分',
              style: TextStyle(
                color: color,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
            const Spacer(),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'score', label: Text('0-10')),
                ButtonSegment(value: 'grade', label: Text('ABCD')),
              ],
              selected: {_ratingMode},
              showSelectedIcon: false,
              style: const ButtonStyle(
                visualDensity: VisualDensity.compact,
                padding: WidgetStatePropertyAll(
                  EdgeInsets.symmetric(horizontal: 8),
                ),
              ),
              onSelectionChanged: (selection) {
                _updateEditorState(() => _ratingMode = selection.first);
              },
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_ratingMode == 'grade')
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'A', label: Text('A')),
              ButtonSegment(value: 'B', label: Text('B')),
              ButtonSegment(value: 'C', label: Text('C')),
              ButtonSegment(value: 'D', label: Text('D')),
            ],
            selected: {_ratingGrade},
            showSelectedIcon: false,
            onSelectionChanged: (selection) {
              _updateEditorState(() => _ratingGrade = selection.first);
            },
          )
        else
          Row(
            children: [
              Expanded(
                child: SliderTheme(
                  data: SliderTheme.of(context).copyWith(
                    trackHeight: 3,
                    activeTrackColor: color.withValues(alpha: 0.8),
                    inactiveTrackColor: color.withValues(alpha: 0.1),
                    thumbColor: Colors.white,
                    thumbShape: const RoundSliderThumbShape(
                      enabledThumbRadius: 7,
                      elevation: 3,
                    ),
                    overlayShape: const RoundSliderOverlayShape(
                      overlayRadius: 14,
                    ),
                  ),
                  child: Slider(
                    value: _rating,
                    min: 0,
                    max: 10,
                    divisions: 100,
                    onChanged: (val) {
                      _updateEditorState(() {
                        _rating = val;
                        _ratingController.text = val.toStringAsFixed(1);
                      });
                    },
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Container(
                width: 52,
                padding: const EdgeInsets.symmetric(vertical: 4),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: TextField(
                  controller: _ratingController,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                    color: color,
                  ),
                  decoration: const InputDecoration(
                    isDense: true,
                    filled: false,
                    border: InputBorder.none,
                    contentPadding: EdgeInsets.zero,
                  ),
                  onSubmitted: (val) {
                    final parsed = double.tryParse(val);
                    if (parsed != null) {
                      _updateEditorState(() {
                        _rating = parsed.clamp(0.0, 10.0);
                        _ratingController.text = _rating.toStringAsFixed(1);
                      });
                    } else {
                      _ratingController.text = _rating.toStringAsFixed(1);
                    }
                  },
                ),
              ),
            ],
          ),
      ],
    );
  }

  Widget _buildProgressCard(Color color) {
    final colorScheme = Theme.of(context).colorScheme;
    return NeumorphicContainer(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.stairs_outlined, size: 20, color: color),
              const SizedBox(width: 8),
              Text(
                _subjectType == 'anime' ? '观看状态与进度' : '阅读状态与进度',
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 16,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<String>(
            initialValue: _status,
            decoration: InputDecoration(
              labelText: '当前状态',
              prefixIcon: Icon(Icons.flag_outlined, color: color),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(color: colorScheme.outlineVariant),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(color: colorScheme.outlineVariant),
              ),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 12,
              ),
            ),
            items: _statusOptions
                .map(
                  (v) => DropdownMenuItem(
                    value: v,
                    child: Text(v, style: const TextStyle(fontSize: 14)),
                  ),
                )
                .toList(),
            onChanged: (v) => _updateEditorState(() => _status = v!),
          ),
          if (_status == '在看') ...[
            const SizedBox(height: 24),
            Center(
              child: Column(
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _buildProgressButton(Icons.remove, () {
                        int current =
                            int.tryParse(_watchedController.text) ?? 0;
                        if (current > 0) {
                          _updateEditorState(
                            () => _watchedController.text = (current - 1)
                                .toString(),
                          );
                        }
                      }, color),
                      Container(
                        width: 120,
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: TextField(
                          controller: _watchedController,
                          keyboardType: TextInputType.number,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 40,
                            fontWeight: FontWeight.w800,
                            color: color,
                            letterSpacing: -1,
                          ),
                          decoration: const InputDecoration(
                            border: InputBorder.none,
                            isDense: true,
                          ),
                        ),
                      ),
                      _buildProgressButton(
                        int.tryParse(_watchedController.text) ==
                                    int.tryParse(_totalController.text) &&
                                int.tryParse(_totalController.text) != 0
                            ? Icons.done
                            : Icons.add,
                        () {
                          int current =
                              int.tryParse(_watchedController.text) ?? 0;
                          int total = int.tryParse(_totalController.text) ?? 0;

                          if (total != 0 && current >= total) {
                            _handleCompletion();
                          } else {
                            final newCount = current + 1;
                            _updateEditorState(
                              () =>
                                  _watchedController.text = newCount.toString(),
                            );

                            // 自动记录到日历
                            if (widget.existingAnime != null) {
                              DatabaseHelper().insertWatchRecord(
                                animeId: widget.existingAnime!['id'],
                                episode: newCount,
                              );
                            }
                          }
                        },
                        int.tryParse(_watchedController.text) ==
                                    int.tryParse(_totalController.text) &&
                                int.tryParse(_totalController.text) != 0
                            ? Colors.green
                            : color,
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        "目标进度 ",
                        style: TextStyle(
                          color: colorScheme.onSurfaceVariant,
                          fontSize: 13,
                        ),
                      ),
                      SizedBox(
                        width: 44,
                        child: TextField(
                          controller: _totalController,
                          keyboardType: TextInputType.number,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: colorScheme.onSurface,
                            fontWeight: FontWeight.bold,
                            fontSize: 15,
                          ),
                          decoration: InputDecoration(
                            isDense: true,
                            enabledBorder: UnderlineInputBorder(
                              borderSide: BorderSide(
                                color: colorScheme.outlineVariant,
                              ),
                            ),
                            contentPadding: const EdgeInsets.only(bottom: 2),
                          ),
                        ),
                      ),
                      Text(
                        _subjectType == 'anime' ? " 集" : " 话",
                        style: TextStyle(
                          color: colorScheme.onSurfaceVariant,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: _buildEpisodeField(
                    _tvEpsController,
                    _subjectType == 'anime' ? 'TV集数' : '话',
                    _subjectType == 'anime'
                        ? Icons.tv_outlined
                        : Icons.menu_book_outlined,
                    color,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: _buildEpisodeField(
                    _spEpsController,
                    _subjectType == 'anime' ? 'SP集数' : 'SP话数',
                    Icons.pets,
                    color,
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildProgressButton(
    IconData icon,
    VoidCallback onPressed,
    Color color,
  ) {
    return Material(
      color: color.withValues(alpha: 0.08),
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          child: Icon(icon, color: color, size: 24),
        ),
      ),
    );
  }

  Widget _buildEpisodeField(
    TextEditingController ctrl,
    String label,
    IconData icon,
    Color color,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return TextField(
      controller: ctrl,
      keyboardType: TextInputType.number,
      decoration: InputDecoration(
        labelText: label,
        prefixIcon: Icon(icon, size: 18, color: color.withValues(alpha: 0.6)),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: colorScheme.outlineVariant),
        ),
        isDense: true,
        labelStyle: const TextStyle(fontSize: 13),
      ),
      style: const TextStyle(fontSize: 14),
    );
  }

  Widget _buildDetailSection(Color color) {
    return NeumorphicContainer(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.more_horiz, color: color.withValues(alpha: 0.7)),
              const SizedBox(width: 8),
              const Text(
                "更多详细信息",
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Divider(height: 1, thickness: 0.5),
          const SizedBox(height: 24),
          // 放送时期与公司
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _airDateController,
                  readOnly: true,
                  onTap: () => _selectAirDate(context),
                  decoration: _buildInputDecoration(
                    _subjectType == 'anime' ? '放送日期' : '出版日期',
                    Icons.calendar_today_outlined,
                    color,
                  ),
                  style: const TextStyle(fontSize: 14),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(child: _buildStudioAutocomplete(color)),
            ],
          ),
          const SizedBox(height: 20),
          // 开始/结束日期
          Row(
            children: [
              Expanded(
                child: _buildDateButton(
                  label: "开始时间",
                  date: _watchStartDate,
                  onTap: () => _selectWatchDate(context, true),
                  color: color,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: _buildDateButton(
                  label: "结束时间",
                  date: _watchFinishDate,
                  onTap: () => _selectWatchDate(context, false),
                  color: color,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _reviewController,
            maxLines: null,
            minLines: 3,
            decoration: _buildInputDecoration(
              '评价与备注',
              Icons.edit_note_outlined,
              color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildReminderCard(Color color) {
    return NeumorphicContainer(
      padding: const EdgeInsets.all(24.0),
      child: _buildReminderSection(color),
    );
  }

  Widget _buildReminderSection(Color color) {
    final colorScheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(
                  Icons.notifications_active_outlined,
                  size: 20,
                  color: color,
                ),
                const SizedBox(width: 8),
                Text(
                  _subjectType == 'anime' ? '追番提醒' : '阅读提醒',
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            Switch(
              value: _isReminderEnabled,
              onChanged: (val) {
                _updateEditorState(() {
                  _isReminderEnabled = val;
                  if (val && _reminderDay == null) {
                    // 默认开启时尝试同步放送日
                    _reminderDay = _broadcastDay;
                    _reminderTime = _broadcastTime;
                  }
                });
              },
              activeThumbColor: color,
            ),
          ],
        ),
        if (_isReminderEnabled) ...[
          const SizedBox(height: 8),
          Row(
            children: [
              _buildReminderDaySelector(color),
              const SizedBox(width: 8),
              _buildReminderTimeSelector(color),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            _subjectType == 'anime'
                ? '开启后，系统将在每周指定时间发送更新通知'
                : '开启后，系统将在每周指定时间发送阅读提醒',
            style: TextStyle(fontSize: 11, color: colorScheme.onSurfaceVariant),
          ),
        ],
      ],
    );
  }

  Widget _buildReminderDaySelector(Color color) {
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return InkWell(
      onTap: () async {
        final int? selectedDay = await showDialog<int>(
          context: context,
          builder: (context) {
            return AlertDialog(
              title: const Text('选择提醒日', style: TextStyle(fontSize: 16)),
              contentPadding: const EdgeInsets.only(top: 12, bottom: 0),
              content: SizedBox(
                width: double.maxFinite,
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: days.length,
                  itemBuilder: (context, index) {
                    final dayIndex = index + 1;
                    return ListTile(
                      title: Text(days[dayIndex - 1]),
                      selected: _reminderDay == dayIndex,
                      selectedColor: color,
                      onTap: () => Navigator.pop(context, dayIndex),
                    );
                  },
                ),
              ),
            );
          },
        );
        if (selectedDay != null) {
          _updateEditorState(() => _reminderDay = selectedDay);
        }
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.05),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Text(
          _reminderDay != null ? days[_reminderDay! - 1] : '选择日期',
          style: TextStyle(fontSize: 13, color: color),
        ),
      ),
    );
  }

  Widget _buildReminderTimeSelector(Color color) {
    return InkWell(
      onTap: () async {
        final TimeOfDay? time = await showTimePicker(
          context: context,
          initialTime: _reminderTime ?? const TimeOfDay(hour: 20, minute: 0),
          initialEntryMode: TimePickerEntryMode.input, // 默认为输入模式，支持键盘输入
        );
        if (time != null) {
          _updateEditorState(() => _reminderTime = time);
        }
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.05),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Text(
          _reminderTime != null
              ? '${_reminderTime!.hour.toString().padLeft(2, '0')}:${_reminderTime!.minute.toString().padLeft(2, '0')}'
              : '选择时间',
          style: TextStyle(fontSize: 13, color: color),
        ),
      ),
    );
  }

  Widget _buildDateButton({
    required String label,
    required DateTime? date,
    required VoidCallback onTap,
    required Color color,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: colorScheme.surfaceContainerHighest,
          border: Border.all(color: colorScheme.outlineVariant),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                color: colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                Icon(
                  Icons.schedule_outlined,
                  size: 14,
                  color: color.withValues(alpha: 0.7),
                ),
                const SizedBox(width: 6),
                Text(
                  date != null
                      ? "${date.year}-${date.month}-${date.day}"
                      : "未设置",
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: date != null
                        ? FontWeight.bold
                        : FontWeight.normal,
                    color: date != null
                        ? colorScheme.onSurface
                        : colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBroadcastDaySelector(Color color) {
    final colorScheme = Theme.of(context).colorScheme;
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return InkWell(
      onTap: () async {
        final int? selectedDay = await showDialog<int>(
          context: context,
          builder: (context) {
            return AlertDialog(
              title: const Text('选择放送日', style: TextStyle(fontSize: 16)),
              contentPadding: const EdgeInsets.only(top: 12, bottom: 0),
              content: SizedBox(
                width: double.maxFinite,
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: days.length + 1,
                  itemBuilder: (context, index) {
                    if (index == 0) {
                      return ListTile(
                        title: const Text(
                          '清除',
                          style: TextStyle(color: Colors.red),
                        ),
                        onTap: () => Navigator.pop(context, 0),
                      );
                    }
                    final dayIndex = index;
                    return ListTile(
                      title: Text(days[dayIndex - 1]),
                      selected: _broadcastDay == dayIndex,
                      selectedColor: color,
                      onTap: () => Navigator.pop(context, dayIndex),
                    );
                  },
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('取消'),
                ),
              ],
            );
          },
        );

        if (selectedDay != null) {
          _updateEditorState(() {
            _broadcastDay = selectedDay == 0 ? null : selectedDay;
            if (_broadcastDay == null) {
              _broadcastTime = null; // 清除星期时联动清除时间
            }
            _syncBroadcastTimeToAirDate();
          });
        }
      },
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: _broadcastDay != null
              ? color.withValues(alpha: 0.1)
              : colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: _broadcastDay != null
                ? color.withValues(alpha: 0.3)
                : colorScheme.outlineVariant,
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.calendar_today_outlined,
              size: 14,
              color: _broadcastDay != null
                  ? color
                  : colorScheme.onSurfaceVariant,
            ),
            const SizedBox(width: 4),
            Text(
              _broadcastDay != null ? days[_broadcastDay! - 1] : '周几',
              style: TextStyle(
                fontSize: 13,
                color: _broadcastDay != null
                    ? color
                    : colorScheme.onSurfaceVariant,
                fontWeight: _broadcastDay != null
                    ? FontWeight.w600
                    : FontWeight.normal,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBroadcastTimeSelector(Color color) {
    final colorScheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: _broadcastDay == null
          ? null // 只有选了周几才能选时间
          : () async {
              final TimeOfDay? time = await showTimePicker(
                context: context,
                initialTime:
                    _broadcastTime ?? const TimeOfDay(hour: 23, minute: 0),
              );

              if (time != null) {
                _updateEditorState(() {
                  _broadcastTime = time;
                  _syncBroadcastTimeToAirDate();
                });
              }
            },
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: _broadcastTime != null
              ? color.withValues(alpha: 0.1)
              : (_broadcastDay == null
                    ? colorScheme.surfaceContainerHighest.withValues(alpha: 0.5)
                    : colorScheme.surfaceContainerHighest),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: _broadcastTime != null
                ? color.withValues(alpha: 0.3)
                : (_broadcastDay == null
                      ? Colors.transparent
                      : colorScheme.outlineVariant),
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.access_time,
              size: 14,
              color: _broadcastTime != null
                  ? color
                  : (_broadcastDay == null
                        ? colorScheme.onSurfaceVariant.withValues(alpha: 0.45)
                        : colorScheme.onSurfaceVariant),
            ),
            const SizedBox(width: 4),
            Text(
              _broadcastTime != null
                  ? '${_broadcastTime!.hour.toString().padLeft(2, '0')}:${_broadcastTime!.minute.toString().padLeft(2, '0')}'
                  : '时间',
              style: TextStyle(
                fontSize: 13,
                color: _broadcastTime != null
                    ? color
                    : (_broadcastDay == null
                          ? colorScheme.onSurfaceVariant.withValues(alpha: 0.45)
                          : colorScheme.onSurfaceVariant),
                fontWeight: _broadcastTime != null
                    ? FontWeight.w600
                    : FontWeight.normal,
              ),
            ),
            if (_broadcastTime != null) ...[
              const SizedBox(width: 4),
              GestureDetector(
                onTap: () {
                  _updateEditorState(() {
                    _broadcastTime = null;
                    _syncBroadcastTimeToAirDate();
                  });
                },
                child: Icon(
                  Icons.close,
                  size: 14,
                  color: color.withValues(alpha: 0.7),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
