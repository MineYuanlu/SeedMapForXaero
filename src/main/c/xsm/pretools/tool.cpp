#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <string>
#include <tuple>
#include <vector>

#include "../../cubiomes/biomes.h"
#include "../../cubiomes/util.h"
namespace xsm::pretools {

int get_biomes_id_to_name(const std::filesystem::path& out_file) {
  static constexpr size_t MAX_BIOMES_NUM = 1024;
  // make parent dir
  if (!std::filesystem::exists(out_file.parent_path())) {
    std::filesystem::create_directories(out_file.parent_path());
  }
  std::ofstream out(out_file);
  if (!out.is_open()) {
    std::cerr << "Failed to open file: " << out_file << std::endl;
    return 1;
  }
  for (int i = 0; i < MAX_BIOMES_NUM; ++i) {
    auto name = biome2str(MC_NEWEST, i);
    if (name == nullptr) continue;
    out << i << '\t' << name << '\n';
  }
  return 0;
}


int help(const std::string& exe, std::string need_func = "") {
  struct Funcs {
    std::string name{};
    std::vector<std::tuple<std::string, bool>>
        args{};  ///< args[0] is arg name, args[1] is arg required
  };
  static const std::vector<Funcs> funcs = {
      {"help", {}},
      {"get_biomes_id_to_name", {{"output_file", true}}},
  };
  const auto out_func = [](std::ostream& out, const Funcs& func) {
    std::cout << "  " << func.name;
    for (const auto& [arg, required] : func.args) {
      std::cout << ' ' << (required ? '<' : '[') << arg
                << (required ? '>' : ']');
    }
    std::cout << std::endl;
  };
  if (need_func.empty()) {
    std::cout << "Usage: " << exe << " <function>" << std::endl;
    for (const auto& func : funcs) {
      out_func(std::cout, func);
    }
    return 0;
  } else {
    for (const auto& func : funcs) {
      if (func.name != need_func) continue;
      std::cerr << "Usage: " << exe;
      out_func(std::cerr, func);
      return 0;
    }
  }
  return 1;  // should not happen
}


}  // namespace xsm::pretools

int main(int argc, char* argv[]) {
  namespace xpt = xsm::pretools;
  if (argc <= 0) return -1;  // should not happen
  if (argc <= 1) return xpt::help(argv[0]);

  const std::string func = argv[1];
  if (func == "get_biomes_id_to_name") {
    if (argc != 3) {
      xpt::help(argv[0], "get_biomes_id_to_name");
      return -2;
    }
    const std::filesystem::path out_file(argv[2]);
    return xpt::get_biomes_id_to_name(out_file);
  } else if (func == "help" || func == "-h" || func == "--help") {
    return xpt::help(argv[0]);
  } else {
    std::cerr << "Unknown function: " << func << std::endl;
    return -3;
  }
}