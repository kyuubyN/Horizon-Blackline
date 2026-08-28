import 'package:flutter/material.dart';

class BrandMark extends StatelessWidget {
  const BrandMark({super.key});

  @override
  Widget build(BuildContext context) => Container(
    width: 42,
    height: 42,
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(14),
      gradient: const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xffff3d69), Color(0xff781744)],
      ),
    ),
    child: const Icon(Icons.account_tree_outlined, color: Colors.white),
  );
}
