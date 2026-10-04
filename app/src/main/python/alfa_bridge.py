"""
Puente entre HDFull (Kotlin/Chaquopy) y el resolver de Alfa.

Expone resolve_video_url(url, page_html, mode) que intenta usar
alfaresolver.decode_video_url() del módulo original de Alfa.

Si el módulo no carga (p.ej. bytecode Python 2 en Python 3),
lanza una excepción con el error exacto en lugar de ocultarlo.
"""
import traceback


def resolve_video_url(url, page_html, mode=2):
    """
    Aplica alfaresolver.decode_video_url(url, page_html, mode).

    Returns:
        str: URL decodificada, o None si no se pudo.

    Raises:
        Exception con el error exacto si el módulo no carga.
    """
    try:
        import alfaresolver
    except Exception as e:
        raise RuntimeError(
            "alfaresolver no se pudo importar: %s\n%s"
            % (e, traceback.format_exc(limit=5))
        )

    if not hasattr(alfaresolver, "decode_video_url"):
        raise RuntimeError(
            "alfaresolver cargado pero sin función decode_video_url. "
            "Atributos: %s" % [a for a in dir(alfaresolver) if not a.startswith("_")][:20]
        )

    try:
        result = alfaresolver.decode_video_url(url, page_html, mode)
        return result
    except Exception as e:
        raise RuntimeError(
            "decode_video_url falló: %s\n%s" % (e, traceback.format_exc(limit=8))
        )


def module_info():
    """Devuelve info de diagnóstico sobre el módulo cargado."""
    try:
        import alfaresolver
        import sys
        return {
            "loaded": True,
            "file": getattr(alfaresolver, "__file__", "?"),
            "has_decode": hasattr(alfaresolver, "decode_video_url"),
            "python": sys.version,
        }
    except Exception as e:
        return {"loaded": False, "error": str(e)}
