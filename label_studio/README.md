# Label Studio 本地审核项目

## 启动

在 PyCharm 中运行 `training/start_label_studio.py`。打开 `http://127.0.0.1:8080`。

首次打开时创建本机 Label Studio 账号。账号只存储在 `D:\environment\label-studio` 的本地数据库中。

## 创建项目

1. 新建项目：`Meguri 日语情绪声学复核`。
2. 将 `meguri_emotion_label_config.xml` 的内容粘贴到 Labeling Interface。
3. 通过 Import 导入 `meguri_emotion_tasks.json`。
4. 每条任务会显示原始 OGG 和去噪 WAV 两个播放器。
5. 标注完成后从 Label Studio 导出 JSON，使用项目中的合并脚本写回原始 CSV。

本地文件服务的根目录是 `D:\program\meguri-pet`，不会把语音上传到云端。

## 在 PyCharm 中回写标注

1. 新建一个临时 Run/Debug Configuration。
2. Script path 选择 `training/merge_label_studio_export.py`。
3. Parameters 填写 Label Studio 导出的 JSON 绝对路径，例如 `D:\\Downloads\\meguri_export.json`。
4. Python interpreter 选择 `D:\environment\anaconda3\envs\label-studio\python.exe`，点击 Run。
5. 脚本会把最新标注写回 `reports/emotion_acoustic_pilot_review.csv`，保留中文原文、英文内部枚举和自由文本情绪描述。

如果重新生成任务或配置，在 PyCharm 运行 `training/prepare_label_studio_project.py` 即可；它会从当前审核 CSV 重建 100 条任务和中文界面配置。
