# 项目：烟丝长度和宽度智能视觉检测 App（Android）

## 项目目标
基于 Android 平板开发一款烟丝检测 App，实现图像采集、图像处理、**长度和宽度**测量、数据上传等功能，检测时间 ≤ 15 分钟，精度 ≤ 0.01mm。

## 技术栈
- 语言：Java / Kotlin
- 图像处理：OpenCV for Android
- 数据格式：JSON，excel
- 上传方式：HTTP API（对接车间综合管理系统）、另存为excel文件
- 开发工具：Android Studio

## 功能模块

### 1. 图像采集
- 调用摄像头拍摄烟丝图像
- 支持手动拍摄或自动触发
- 图像分辨率 ≥ 3264×2448

### 2. 图像处理（OpenCV）
- 灰度化
- 双边滤波 + 高斯滤波
- **三角形法二值化** 或 **颜色阈值检测**（支持可视化调参）
- 轮廓提取 + **Douglas-Peucker 多边形拟合**
- 计算烟丝**长度和宽度**：
  - 长度：**骨架路径长度**（Zhang-Suen细化算法，适合曲线烟丝）
  - 宽度：**法线多点中位数**（适合曲线烟丝）
- 畸变矫正（支持相机标定）
- 输出平均长度和宽度值

### 3. 数据管理
- 保存检测结果（JSON）,或者excel文件
- 上传至指定 API
- 支持历史记录查看

### 4. UI 交互
- 操作引导界面
- 实时显示图像与检测结果
- 标记烟丝轮廓与测量线（长度和宽度、骨架路径）

## 检测流程
1. 在**黑色背景布**上放置 **1-10根黄色烟丝**
2. 拍摄图像
3. 图像处理自动检测**长度和宽度**
4. 显示结果并保存/上传

## 测试要求
- 连续运行 5 小时无崩溃
- 检测成功率 100%
- 精度 ≤ 0.01mm
- 单次检测时间 ≤ 15 分钟

## 算法优化记录

### 1. 颜色通道语义修正与可视化调参
- **修正**：OpenCV 使用 BGR 格式存储彩色图像，代码中的通道索引已修正
- **新增**：`TuningConfig` 类，支持以下参数可视化调参：
  - HSV 颜色阈值（H/S/V 上下限）
  - RGB 颜色阈值（R/G/B 阈值）
  - 形态学操作参数（核大小、开/闭运算迭代次数）
  - 宽度采样点数
- **导出功能**：支持导出 Python 参数配置文件，方便在 OpenCV 调试工具中调参

### 2. 长度计算：骨架路径长度
- **原方法**：最小外接矩形长边、轮廓周长一半
- **新方法**：**骨架路径长度**（Skeleton-based Length）
  - 使用 Zhang-Suen 细化算法提取烟丝骨架
  - 计算骨架上所有点的累积距离
  - **优势**：能够准确反映曲线烟丝的真实长度

### 3. 宽度计算：法线多点中位数
- **原方法**：最小外接矩形短边、距离变换
- **新方法**：**法线多点中位数**（Normal-based Median Width）
  - 在骨架的多个采样点处，沿法线方向测量宽度
  - 取所有宽度测量的**中位数**
  - **优势**：对异常值更鲁棒，适合测量曲线烟丝

### 4. 畸变矫正与固定工位
- **畸变矫正**：
  - 支持相机标定（棋盘格标定）
  - 使用 `Calib3d.undistort()` 进行畸变校正
- **固定工位配置**：
  - 相机高度（mm）
  - 焦距（mm）
  - 传感器宽度（mm）
  - 自动计算像素比例：`pixel_size = (sensor_width / image_width) * (camera_height / focal_length)`

### 5. 基准测试与评估指标
- **基准测试数据集**：
  - 支持 JSON 格式的真值标注
  - 包含：图像路径、每根烟丝的真实宽度和长度
- **评估指标**：
  - **MAE**（Mean Absolute Error，平均绝对误差）
  - **RMSE**（Root Mean Square Error，均方根误差）
  - **95分位误差**（95th Percentile Error）
  - **成功率**（误差 ≤ 0.01mm 的比例）
  - **平均处理时间**

## 参考资料
- OpenCV Android 官方文档
- 三角形二值化算法
- Douglas-Peuker 算法实现
- Zhang-Suen 骨架细化算法
- 相机标定与畸变矫正

## 文件结构
```
app/src/main/java/com/tobacco/detection/
├── processing/
│   └── TobaccoProcessor.kt      # 核心图像处理算法
├── testing/
│   └── BenchmarkTester.kt       # 基准测试工具
├── data/
│   └── Models.kt                # 数据模型（含基准测试模型）
└── utils/
    └── CalibrationUtils.kt      # 相机标定工具
```

## 调参配置文件示例
```python
# 导出路径：/sdcard/tobacco/tuning_params.py

# HSV 颜色阈值
HSV_LOWER = np.array([15, 40, 30])
HSV_UPPER = np.array([45, 255, 255])

# RGB 颜色阈值
RGB_R_LOWER = 150
RGB_G_LOWER = 130
RGB_B_UPPER = 160

# 形态学参数
MORPH_KERNEL_SIZE = 5
OPEN_ITERATIONS = 2
CLOSE_ITERATIONS = 2

# 测量参数
WIDTH_SAMPLE_COUNT = 8
SKELETON_THRESHOLD = 10

# 工位参数
CAMERA_HEIGHT_MM = 300
FOCAL_LENGTH_MM = 4.5
SENSOR_WIDTH_MM = 6.17
```

## 基准测试JSON格式示例
```json
{
  "id": "dataset_001",
  "name": "测试集1",
  "image_path": "/sdcard/tobacco/test_images/img_001.jpg",
  "ground_truths": [
    {
      "tobacco_index": 0,
      "width_mm": 0.65,
      "length_mm": 12.5,
      "notes": "直烟丝"
    },
    {
      "tobacco_index": 1,
      "width_mm": 0.68,
      "length_mm": 11.8,
      "notes": "弯曲烟丝"
    }
  ]
}
```
