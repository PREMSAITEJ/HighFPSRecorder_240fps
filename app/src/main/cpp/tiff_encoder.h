#ifndef HIGHFPS_TIFF_ENCODER_H
#define HIGHFPS_TIFF_ENCODER_H

#include <cstdint>
#include <string>
#include <vector>

/**
 * 8-bit grayscale TIFF encoder
 * Generates uncompressed TIFF frames with proper header/IFD structure
 * Optimized for fast sequential writes at 240 FPS
 */
class TIFFEncoder {
public:
    TIFFEncoder(int width, int height, const char* output_dir);
    ~TIFFEncoder();

    // Encode grayscale frame to TIFF file
    bool encode_grayscale_tiff(const uint8_t* gray_data, uint64_t frame_number);

    // Statistics
    uint64_t get_encoded_count() const;

private:
    struct TIFFHeader {
        uint8_t byte_order[2];      // "II" = little-endian
        uint16_t magic;             // 42
        uint32_t ifd_offset;        // Offset to IFD
    } __attribute__((packed));

    struct TIFFTag {
        uint16_t tag;               // Tag ID
        uint16_t type;              // Data type
        uint32_t count;             // Count of values
        uint32_t value_or_offset;   // Value or offset
    } __attribute__((packed));

    // TIFF generation
    void write_tiff_header(std::vector<uint8_t>& buffer);
    void write_ifd(std::vector<uint8_t>& buffer, uint32_t strip_offset,
                   uint32_t strip_byte_count);
    std::string get_frame_filename(uint64_t frame_number) const;

    // Member variables
    int width_;
    int height_;
    std::string output_dir_;
    uint64_t encoded_count_;

    // Pre-calculated sizes
    uint32_t y_plane_size_;
    uint32_t pixel_data_size_;
};

#endif // HIGHFPS_TIFF_ENCODER_H
