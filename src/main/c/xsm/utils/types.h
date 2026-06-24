typedef unsigned char uint8_t;
typedef signed char int8_t;
typedef unsigned short uint16_t;
typedef signed short int16_t;
typedef unsigned int uint32_t;
typedef signed int int32_t;
typedef unsigned long long uint64_t;
typedef signed long long int64_t;

#if defined(__cplusplus)
static_assert(sizeof(uint8_t) == 1);
static_assert(sizeof(int8_t) == 1);
static_assert(sizeof(uint16_t) == 2);
static_assert(sizeof(int16_t) == 2);
static_assert(sizeof(uint32_t) == 4);
static_assert(sizeof(int32_t) == 4);
static_assert(sizeof(uint64_t) == 8);
static_assert(sizeof(int64_t) == 8);
#endif