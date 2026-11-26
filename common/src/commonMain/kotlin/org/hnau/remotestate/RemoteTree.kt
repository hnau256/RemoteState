package org.hnau.remotestate

import org.hnau.commons.gen.fold.annotations.Fold

@Fold
sealed interface RemoteTree<out L> {

    data class Leaf<out L>(
        val value: L,
    ) : RemoteTree<L>

    data class Branch<out L>(
        val children: Map<RemotePathSegment, RemoteTree<L>>,
    ) : RemoteTree<L>

    companion object {

        fun <L> build(
            leaves: Map<RemotePath, L>,
        ): RemoteTree<L> = leaves[RemotePath.root]
            ?.let { value -> Leaf(value) }
            ?: Branch(
                children = leaves
                    .entries
                    .groupBy(
                        keySelector = { (path) -> path.segments.first() },
                        valueTransform = { (path, value) -> RemotePath(path.segments.drop(1)) to value },
                    )
                    .mapValues { (_, children) -> build(children.toMap()) },
            )
    }
}

fun <L> RemoteTree<L>.find(
    path: RemotePath,
): Pair<L, RemotePath>? = fold(
    ifLeaf = { value -> value to path },
    ifBranch = { children ->
        path
            .split()
            ?.let { (head, tail) -> children[head]?.find(tail) }
    },
)
