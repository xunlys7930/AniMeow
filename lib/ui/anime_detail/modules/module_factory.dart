import 'package:flutter/material.dart';

import '../detail_layout.dart';
import '../detail_props.dart';
import 'detail_meta_module.dart';
import 'reminder_module.dart';
import 'review_module.dart';
import 'siblings_module.dart';
import 'status_progress_module.dart';
import 'tags_module.dart';

/// 根据 [DetailModule] 枚举值返回对应的模块 widget。
///
/// 各模块内部已经处理"无内容时返回 SizedBox.shrink()"，所以 layout
/// 不需要额外判断空态。
Widget buildDetailModule(DetailModule module, DetailViewProps props) {
  switch (module) {
    case DetailModule.statusProgress:
      return StatusProgressModule(props: props);
    case DetailModule.tags:
      return TagsModule(props: props);
    case DetailModule.review:
      return ReviewModule(props: props);
    case DetailModule.detailMeta:
      return DetailMetaModule(props: props);
    case DetailModule.reminder:
      return ReminderModule(props: props);
    case DetailModule.siblings:
      return SiblingsModule(props: props);
  }
}
