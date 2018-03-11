// Copyright: 2017 - 2018 Sam Halliday
// License: http://www.gnu.org/licenses/lgpl-3.0.en.html

package scalaz

import Predef.ArrowAssoc
import scala.reflect.macros.whitebox.Context

abstract class DerivingCommon {
  val c: Context
  import c.universe._

  protected def isIde: Boolean =
    c.universe.isInstanceOf[scala.tools.nsc.interactive.Global]

  protected def debug(t: Tree) = {
    Predef.println(showRaw(t))
    Predef.println(showCode(t))
  }

  // some classes that add type hints around what a Tree contains
  protected case class TreeTypeName(tree: Tree) {
    def toTermName: TreeTermName =
      TreeTermName(tree match {
        case Ident(name)        => Ident(name.toTermName)
        case Select(qual, name) => Select(qual, name.toTermName)
      })
  }
  protected case class TreeTermName(tree: Tree) {
    def toTypeName: TreeTypeName =
      TreeTypeName(tree match {
        case Ident(name)        => Ident(name.toTypeName)
        case Select(qual, name) => Select(qual, name.toTypeName)
      })
  }
  protected case class TermAndType(term: TreeTermName, cons: TreeTypeName)

  protected def createCompanion(data: ClassDef): ModuleDef = {
    val mods =
      if (data.mods.hasFlag(Flag.PRIVATE))
        Modifiers(Flag.PRIVATE, data.mods.privateWithin)
      else if (data.mods.hasFlag(Flag.PROTECTED))
        Modifiers(Flag.PROTECTED, data.mods.privateWithin)
      else NoMods

    atPos(data.pos)(
      // if we use ModuleDef directly, it doesn't insert the
      // constructor.
      c.internal.reificationSupport.SyntacticObjectDef(
        mods,
        data.name.toTermName,
        Nil,
        Nil,
        noSelfType,
        Nil
      )
    )
  }

  protected def findTypeclasses(): List[(String, TermAndType)] =
    c.prefix.tree.children.collect {
      case s @ Select(_, t) if t != termNames.CONSTRUCTOR => TreeTermName(s)
      case i @ Ident(_)                                   => TreeTermName(i)
    }.map { ttn =>
      memberName(ttn.tree) -> TermAndType(ttn, ttn.toTypeName)
    }

  private def memberName(t: Tree): String =
    "_deriving_" + t.toString.toLowerCase.replace(".", "_")

  protected def regenModule(comp: ModuleDef, extras: List[Tree]): ModuleDef =
    atPos(comp.pos)(
      treeCopy.ModuleDef(
        comp,
        comp.mods,
        comp.name,
        treeCopy.Template(comp.impl,
                          comp.impl.parents,
                          comp.impl.self,
                          comp.impl.body ::: extras)
      )
    )

  protected def genImplicitVal(
    memberName: TermName,
    typeclass: TermAndType,
    c: ClassDef
  ) =
    ValDef(
      Modifiers(Flag.IMPLICIT),
      memberName,
      AppliedTypeTree(typeclass.cons.tree, List(Ident(c.name))),
      toGen(typeclass.cons.tree, Ident(c.name))
    )

  protected def genImplicitDef(
    memberName: TermName,
    typeclass: TermAndType,
    c: ClassDef
  ) = {
    val implicits =
      List(
        c.tparams.zipWithIndex.map {
          case (t, i) =>
            ValDef(
              Modifiers(Flag.IMPLICIT | Flag.PARAM),
              TermName(s"evidence$$$i"),
              AppliedTypeTree(typeclass.cons.tree, List(Ident(t.name))),
              EmptyTree
            )
        }
      )

    val a = AppliedTypeTree(
      Ident(c.name),
      c.tparams.map(tp => Ident(tp.name))
    )

    DefDef(
      Modifiers(Flag.IMPLICIT),
      memberName,
      c.tparams,
      implicits,
      AppliedTypeTree(typeclass.cons.tree, List(a)),
      toGen(typeclass.cons.tree, a)
    )
  }

  protected def genObjectImplicitVal(
    memberName: TermName,
    typeclass: TermAndType,
    comp: ModuleDef
  ) = {
    val a = SingletonTypeTree(Ident(comp.name.toTermName))
    ValDef(
      Modifiers(Flag.IMPLICIT),
      memberName,
      AppliedTypeTree(
        typeclass.cons.tree,
        List(a)
      ),
      toGen(typeclass.cons.tree, a)
    )
  }

  protected def toGen(f: Tree, a: Tree): Tree
}
