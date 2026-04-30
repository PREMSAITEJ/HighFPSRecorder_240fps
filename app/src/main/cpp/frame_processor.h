#ifndef HIGHFPS_FRAME_PROCESSOR_H
#define HIGHFPS_FRAME_PROCESSOR_H

#include <cstdint>
#include <atomic>
#include <vector>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <memory>

class FrameBuffer;
class TIFFEncoder;

/**
 * Raw frame processor for 240 FPS sensor capture
 * - Accepts Y-plane (luminance) data directly from camera sensor
 * - Corrects stride for Samsung memory alignment
 * - Queues frames for parallel TIFF encoding
 * - NO video encoding - pure raw frame output
 */
class FrameProcessor {
public:
    FrameProcessor(int width, int height, const char* output_dir);
    ~FrameProcessor();

    // Lifecycle
    void start_capturing();
    void stop_capturing();
    bool is_capturing() const;

    // Raw Y-plane frame handling (direct from camera)
    void process_raw_yplane(const uint8_t* y_data, int pixel_stride, int row_pitch);

    // Statistics
    uint64_t get_frame_count() const;
    uint64_t get_dropped_frames() const;
    uint64_t get_errors() const;
    double get_avg_processing_time_ms() const;

private:
    // Worker threads
    void encoding_worker();

    // Stride correction for sensor alignment
    void correct_stride(const uint8_t* src, int src_stride,
                       uint8_t* dst, int dst_width, int dst_height);

    // Member variables
    int width_;
    int height_;
    std::string output_dir_;
    std::atomic<bool> is_capturing_;
    std::atomic<uint64_t> frame_count_;
    std::atomic<uint64_t> dropped_frames_;
    std::atomic<uint64_t> error_count_;

    // Threading
    std::queue<std::pair<uint8_t*, uint64_t>> frame_queue_;
    std::mutex queue_mutex_;
    std::condition_variable queue_cv_;
    std::vector<std::thread> encoder_threads_;
    static constexpr int NUM_ENCODER_THREADS = 4;
    static constexpr int MAX_QUEUE_SIZE = 60;  // ~250ms buffer at 240fps

    // Timing statistics
    std::vector<double> processing_times_;
    mutable std::mutex timing_mutex_;

    // Buffers
    std::unique_ptr<FrameBuffer> frame_buffer_;
    std::unique_ptr<TIFFEncoder> tiff_encoder_;
};

#endif // HIGHFPS_FRAME_PROCESSOR_H
